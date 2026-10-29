# ADR-0011 — Model-based analysis: Python jobs, decision model for triage, LLM for explanations

- **Status:** Proposed
- **Date:** 2026-10-29

## Context

From M7 on, the platform analyses its own data with AI models: anomaly detection first ([PRD-007](../prd/PRD-007-m7-anomaly-detection.md)), then site digests, maintenance forecasts and natural-language rules. [ADR-0001](ADR-0001-runtime-and-languages.md) places platform services in Java and the AI agent in Python; [ADR-0010](ADR-0010-ai-agent.md) covers the interactive agent only. These analyses are neither request-driven services nor conversations: they run on a schedule, compute statistics over measurement history, ask models closed questions, and store results that people review. Two kinds of models are available in the environment's Foundry account: `gpt-6-luna` (generative) and `Microsoft-Decision-1` (returns calibrated probabilities for a fixed set of answers, generates no text).

## Decision

1. **Model-based analyses are Python workloads** (uv, same toolchain as the agent), run as scheduled **Container Apps jobs**, one per analysis, extending ADR-0001's "AI in Python" from the agent to all model-based analysis. Platform services and APIs stay Java.
2. **Statistics select, models decide, LLMs explain.** Deterministic statistics on stored data choose a small set of candidates; **closed decisions** (classification, severity, routing, yes/no) go to the **decision model**, whose probabilities are compared with configured thresholds; the **LLM only writes explanations** of decisions already made, from the data used for them.
3. **Every automated decision is recorded** with its inputs summary, all answer probabilities, the model deployment and version, the threshold applied, and the outcome; low-confidence results are routed to human review, never acted on automatically.
4. **Models are optional per environment**: when a model deployment is missing or unavailable, the analyses that need it are disabled; nothing else in the platform depends on them.
5. Results are stored in PostgreSQL and served by the existing platform API (Java, same authorization as other data); the analyses never call the platform API with a user's identity.

## Why hard to reverse

The language and hosting of analysis jobs determine their code, packaging and libraries; the decision record (probabilities, model version, threshold, review) becomes part of the data model, the portal, the assistant's tools and any audit of automated findings. Moving decisions to the LLM, or explanations into decisions, would change how confidence is expressed everywhere.

## Consequences

- A second Python workload type (jobs), sharing the agent's dependency, lint and test conventions and CI job pattern.
- Decision-model calls are cheap and bounded (one call per candidate); LLM calls only for the few confident findings a person will read.
- Calibration must be checked on the platform's own data before thresholds are trusted; thresholds are configuration.
- `Microsoft-Decision-1` is in preview: the decision interface is isolated so another scorer can replace it.

## Alternatives considered

| Option | Why not |
|---|---|
| Java jobs (ADR-0001 platform stack) | Statistics and model SDKs are more natural in Python; the agent already established the Python AI stack. |
| LLM for classification (fixed-choice prompt) | No calibrated probabilities; harder to threshold and audit consistently. |
| Analyses inside the AI agent | The agent acts per user request with the user's token; scheduled analyses are platform-level and must not depend on a conversation. |
| Streaming analysis on the measurement topic | More moving parts (state per device); a 5-minute schedule on stored data meets the latency need. |
