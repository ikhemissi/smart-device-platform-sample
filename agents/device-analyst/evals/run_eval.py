"""Evaluation of the deployed device analyst (M6 tasks 6.1/6.2).

1. Collect: asks every question of questions.jsonl through API Management with your own token (follow-ups in the same
   conversation as their first question), recording the streamed answer, the tool calls and the time to first text.
2. Context: replays each tool call with the agent's own tool code and the same token, to give the groundedness judge
   the data the model saw (read seconds later, so live values may differ marginally).
3. Evaluate with azure-ai-evaluation `evaluate()`: built-in Groundedness, Relevance and ToolCallAccuracy (judged by the
   environment's model deployment, Entra authentication) and a code-based checks evaluator (expected tools, mentions,
   language, refusals, unknown devices, responsiveness).

Usage (from agents/device-analyst, after ../../scripts/agent-env.sh):
    uv run --group eval python evals/run_eval.py [--only id1,id2] [--skip-judges] [--rescore]
Results: evals/results/collected.jsonl, evals/results/evaluation.json, summary printed and written to
evals/results/summary.json.
"""

import argparse
import asyncio
import json
import os
import re
import statistics
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx
from azure.core.credentials import AccessToken
from azure.identity import AzureCliCredential, CredentialUnavailableError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from device_analyst.analyst import INSTRUCTIONS
from device_analyst.config import Settings
from device_analyst.platform_api import PlatformApi, caller_token
from device_analyst.tools import TurnRecorder, build_tools, turn

HERE = Path(__file__).parent
RESULTS = HERE / "results"
FIRST_TEXT_TARGET_SECONDS = 5.0
# Acceptance thresholds (agents/device-analyst/README.md). Judge scores are 1-5 averages; refusals and unknown devices
# are expected to score lower on relevance since they do not answer the question by design.
THRESHOLDS = {
    "first_text_within_5s_ratio": 0.9,
    "checks.passed": 1.0,
    "groundedness.groundedness": 4.0,
    "relevance.relevance": 3.5,
    "tool_call_accuracy.tool_call_accuracy": 3.5,
}
APOSTROPHE = "['\u2019]"
REFUSAL = re.compile(
    rf"can(?:not|{APOSTROPHE}t)|unable|not able|only (?:analy|read|view)|read-only|ne peux|lecture seule", re.IGNORECASE
)
NOT_FOUND = re.compile(
    rf"(?:can(?:not|{APOSTROPHE}t)|could(?: not|n{APOSTROPHE}t)|unable to) find|not found"
    rf"|does(?: not|n{APOSTROPHE}t) exist|no device",
    re.IGNORECASE,
)
FRENCH = {"le", "la", "les", "des", "est", "sont", "de", "du", "en", "une", "aucun", "capteurs", "sur", "et", "pour"}
ENGLISH = {"the", "is", "are", "of", "and", "devices", "on", "for", "with", "no", "over", "at"}


class CachedCliCredential:
    """Azure CLI tokens fetched once per scope and reused until 5 minutes before expiry. The judges ask for a token on
    every call; each `az` invocation can take over 10 s (AzureCliCredential's default timeout), so in parallel most
    calls would fail."""

    def __init__(self) -> None:
        self._cli = AzureCliCredential(process_timeout=120)
        self._tokens: dict[tuple[str, ...], AccessToken] = {}
        self._lock = threading.Lock()

    def get_token(self, *scopes: str, **kwargs: Any) -> AccessToken:
        with self._lock:
            token = self._tokens.get(scopes)
            if token is None or token.expires_on - time.time() < 300:
                try:
                    token = self._cli.get_token(*scopes)
                except CredentialUnavailableError:
                    token = self._cli.get_token(*scopes)  # a busy machine can make `az` time out once
                self._tokens[scopes] = token
            return token


CREDENTIAL = CachedCliCredential()


# --- Collection -----------------------------------------------------------------------------------------------


async def ask(client: httpx.AsyncClient, token: str, conversation: str, question: dict[str, Any]) -> dict[str, Any]:
    body: dict[str, Any] = {"content": question["query"]}
    if question.get("device_id"):
        body["context"] = {"deviceId": question["device_id"]}
    started = time.monotonic()
    first_text = None
    text, tools, citations, error = [], [], [], None
    async with client.stream(
        "POST",
        f"/agent/conversations/{conversation}/messages",
        json=body,
        headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"},
        timeout=150,
    ) as response:
        if response.status_code != 200:
            error = f"HTTP {response.status_code}: {(await response.aread()).decode()[:200]}"
        else:
            event = None
            async for line in response.aiter_lines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:") and event:
                    data = json.loads(line[5:])
                    if event == "delta":
                        first_text = first_text or time.monotonic() - started
                        text.append(data["text"])
                    elif event == "tool":
                        tools.append(data)
                    elif event == "done":
                        citations = data.get("citations", [])
                    elif event == "error":
                        error = data.get("detail")
    return {
        "response": "".join(text),
        "tool_calls": [
            {"type": "tool_call", "tool_call_id": f"call_{i}", "name": t["name"], "arguments": t["arguments"]}
            for i, t in enumerate(tools)
        ],
        "citations": citations,
        "first_text_seconds": round(first_text, 2) if first_text else None,
        "total_seconds": round(time.monotonic() - started, 2),
        "error": error,
    }


async def replay(tools: dict[str, Any], calls: list[dict[str, Any]]) -> str:
    """The data the tools returned (re-read), as the groundedness context."""
    parts = []
    for call in calls:
        tool = tools.get(call["name"])
        if tool is None:
            continue
        reset = turn.set(TurnRecorder())
        try:
            result = await tool.func(**call["arguments"])
        except Exception as e:  # noqa: BLE001 - recorded as context
            result = f"error: {e}"
        finally:
            turn.reset(reset)
        parts.append(f"{call['name']}({json.dumps(call['arguments'], ensure_ascii=False)}) -> {result}")
    return "\n".join(parts) or "No data was retrieved."


async def collect(questions: list[dict[str, Any]], settings: Settings) -> list[dict[str, Any]]:
    scope = f"{settings.api_audiences[0]}/.default"
    token = CREDENTIAL.get_token(scope).token
    caller_token.set(token)
    rows: list[dict[str, Any]] = []
    conversations: dict[str, str] = {}
    transcripts: dict[str, list[dict[str, str]]] = {}
    async with httpx.AsyncClient(base_url=settings.api_base_url.rstrip("/"), timeout=30) as client:
        tools = {t.name: t for t in build_tools(PlatformApi(client))}
        for question in questions:
            parent = question.get("follow_up_of")
            if parent and parent in conversations:
                conversation = conversations[parent]
            else:
                created = await client.post("/agent/conversations", headers={"Authorization": f"Bearer {token}"})
                created.raise_for_status()
                conversation = created.json()["id"]
            conversations[question["id"]] = conversation
            started_at = datetime.now(UTC).isoformat(timespec="seconds")
            answer = await ask(client, token, conversation, question)
            context = await replay(tools, answer["tool_calls"])
            row = {
                "device_id": None,
                "follow_up_of": None,
                "expected_device": None,
                **question,
                **answer,
                "retrieved": context,
            }
            # The tool-call judge sees the conversation as the agent did: instructions, per-turn context, earlier turns.
            situation = f"Current time: {started_at}."
            if question.get("device_id"):
                situation += f" The user is viewing the page of device {question['device_id']}."
            history = transcripts.get(parent, []) if parent else []
            row["conversation"] = [
                {"role": "system", "content": f"{INSTRUCTIONS}\n{situation}"},
                *history,
                {"role": "user", "content": question["query"]},
            ]
            transcripts[question["id"]] = [
                *row["conversation"][1:],
                {"role": "assistant", "content": answer["response"]},
            ]
            rows.append(row)
            failure = f" ERROR {answer['error']}" if answer["error"] else ""
            print(
                f"{question['id']:<26} first text {answer['first_text_seconds']} s, total {answer['total_seconds']} s, "
                f"tools {[c['name'] for c in answer['tool_calls']]}{failure}"
            )
            # Stay under the per-user limit of 20 messages per minute.
            await asyncio.sleep(3.5)
    return rows


# --- Evaluators -----------------------------------------------------------------------------------------------


class ChecksEvaluator:
    """Objective checks per question: expected tools, mentions, language, refusals, unknown devices, latency."""

    def __call__(
        self,
        *,
        response: str,
        tool_calls: list[dict[str, Any]],
        category: str,
        expected_tools: list[str],
        must_mention: list[str],
        language: str,
        first_text_seconds: float | None = None,
        expected_device: str | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        called = [c["name"] for c in tool_calls or []]
        checks: dict[str, bool] = {}
        if category == "refusal":
            checks["declines"] = bool(REFUSAL.search(response))
        elif expected_tools:
            checks["expected_tool"] = any(name in called for name in expected_tools)
        if category == "unknown":
            checks["says_not_found"] = bool(NOT_FOUND.search(response))
        if must_mention:
            checks["mentions"] = all(m.lower() in response.lower() for m in must_mention)
        if expected_device:
            checks["right_device"] = any(
                expected_device in json.dumps(c.get("arguments", {})) for c in tool_calls or []
            )
        checks["language"] = detect_language(response) == language
        checks["first_text_within_5s"] = (
            first_text_seconds is not None and first_text_seconds <= FIRST_TEXT_TARGET_SECONDS
        )
        failed = [name for name, ok in checks.items() if not ok]
        return {
            "passed": 0.0 if [f for f in failed if f != "first_text_within_5s"] else 1.0,
            "first_text_within_5s": 1.0 if checks["first_text_within_5s"] else 0.0,
            "failed_checks": ",".join(failed),
        }


def detect_language(text: str) -> str:
    words = re.findall(r"[a-zàâçéèêëîïôûùüÿœ']+", text.lower())
    french = sum(w in FRENCH for w in words)
    english = sum(w in ENGLISH for w in words)
    return "fr" if french > english else "en"


def run_evaluation(data: Path, settings: Settings, skip_judges: bool) -> dict[str, Any]:
    from azure.ai.evaluation import (
        AzureOpenAIModelConfiguration,
        GroundednessEvaluator,
        RelevanceEvaluator,
        ToolCallAccuracyEvaluator,
        evaluate,
    )

    evaluators: dict[str, Any] = {"checks": ChecksEvaluator()}
    config: dict[str, Any] = {
        "checks": {
            "column_mapping": {
                name: "${data." + name + "}"
                for name in (
                    "response",
                    "tool_calls",
                    "category",
                    "expected_tools",
                    "must_mention",
                    "language",
                    "first_text_seconds",
                    "expected_device",
                )
            }
        },
    }
    if not skip_judges:
        model = AzureOpenAIModelConfiguration(
            azure_endpoint=settings.openai_endpoint, azure_deployment=settings.model_deployment
        )
        judge = {"model_config": model, "credential": CREDENTIAL, "is_reasoning_model": True}
        evaluators |= {
            "groundedness": GroundednessEvaluator(**judge),
            "relevance": RelevanceEvaluator(**judge),
            "tool_call_accuracy": ToolCallAccuracyEvaluator(**judge),
        }
        definitions = [
            {"name": t.name, "description": t.description, "parameters": t.parameters()} for t in build_tools(None)
        ]  # type: ignore[arg-type]
        (RESULTS / "tool_definitions.json").write_text(json.dumps(definitions))
        config |= {
            "groundedness": {
                "column_mapping": {
                    "query": "${data.query}",
                    "response": "${data.response}",
                    "context": "${data.context}",
                }
            },
            "relevance": {"column_mapping": {"query": "${data.query}", "response": "${data.response}"}},
            "tool_call_accuracy": {
                "column_mapping": {
                    "query": "${data.conversation}",
                    "tool_calls": "${data.tool_calls}",
                    "tool_definitions": "${data.tool_definitions}",
                }
            },
        }
        rows = [json.loads(line) for line in data.read_text().splitlines()]
        with data.open("w") as f:
            for row in rows:
                # Groundedness is judged against everything the agent was given: its instructions, the per-turn
                # situation (current time, device context) and the data its tools returned.
                context = f"{row['conversation'][0]['content']}\n\nData retrieved by the tools:\n{row['retrieved']}"
                f.write(
                    json.dumps(row | {"tool_definitions": definitions, "context": context}, ensure_ascii=False) + "\n"
                )
    return evaluate(
        data=str(data),
        evaluators=evaluators,
        evaluator_config=config,
        evaluation_name="device-analyst",
        output_path=str(RESULTS / "evaluation.json"),
    )


def summarise(rows: list[dict[str, Any]], result: dict[str, Any]) -> dict[str, Any]:
    first = [r["first_text_seconds"] for r in rows if r["first_text_seconds"] is not None]
    metrics = {k: round(v, 3) for k, v in result.get("metrics", {}).items() if isinstance(v, int | float)}
    per_row = result.get("rows", [])
    failures = {
        r.get("inputs.id"): r.get("outputs.checks.failed_checks")
        for r in per_row
        if r.get("outputs.checks.failed_checks")
    }
    return {
        "questions": len(rows),
        "errors": [r["id"] for r in rows if r["error"]],
        "first_text_within_5s_ratio": round(sum(s <= FIRST_TEXT_TARGET_SECONDS for s in first) / len(rows), 3),
        "first_text_p50_seconds": round(statistics.median(first), 2) if first else None,
        "first_text_p90_seconds": round(statistics.quantiles(first, n=10)[-1], 2) if len(first) > 1 else None,
        "metrics": metrics,
        "failed_checks": failures,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="comma-separated question ids")
    parser.add_argument("--skip-judges", action="store_true", help="code-based checks only (no judge model calls)")
    parser.add_argument("--rescore", action="store_true", help="evaluate results/collected.jsonl again without asking")
    parser.add_argument(
        "--judge-concurrency",
        type=int,
        default=2,
        help="parallel judge calls per evaluator; the judges share the agent's tokens-per-minute quota",
    )
    args = parser.parse_args()
    os.environ["PF_WORKER_COUNT"] = str(args.judge_concurrency)
    settings = Settings()
    questions = [json.loads(line) for line in (HERE / "questions.jsonl").read_text().splitlines() if line.strip()]
    if args.only:
        wanted = set(args.only.split(","))
        questions = [q for q in questions if q["id"] in wanted]
    RESULTS.mkdir(exist_ok=True)
    data = RESULTS / "collected.jsonl"
    if args.rescore:
        rows = [json.loads(line) for line in data.read_text().splitlines()]
    else:
        rows = asyncio.run(collect(questions, settings))
        data.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    summary = summarise(rows, run_evaluation(data, settings, args.skip_judges))
    values = {"first_text_within_5s_ratio": summary["first_text_within_5s_ratio"], **summary["metrics"]}
    summary["below_threshold"] = {
        name: values.get(name)
        for name, minimum in THRESHOLDS.items()
        if name in values
        and values[name] < minimum
        and not (args.skip_judges and "." in name and name != "checks.passed")
    }
    (RESULTS / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    if summary["below_threshold"] or summary["errors"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
