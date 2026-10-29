# Demo guide — M6: AI device analyst

Shows an assistant that answers questions about devices, measurements and alerts in natural language, grounded in the platform's data, with the signed-in user's permissions, and that never changes anything ([PRD-006](../prd/PRD-006-m6-ai-analyst.md), ADR-0010). Duration: about 20 minutes.

## Before the demo

The environment must have M1–M6 deployed, the simulator running, and at least a day of history (30 days with `scripts/backfill-history.sh` makes trend questions more interesting):

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
```

The **Assistant** button appears in the portal header once the agent is deployed (`config.json` gets `assistantEnabled: true` when the portal is deployed; `azd env set PORTAL_ASSISTANT false` hides it).

**Optional, to show prompts and answers in traces:** content capture is off by default (traces only hold metadata such as token counts). For a demo, enable it and provision (this restarts the agent):

```bash
azd env set AGENT_CAPTURE_CONTENT true && azd provision
```

Requirements on the presenter's machine: `az`, `azd`, `curl`, Python 3. Useful variables:

```bash
eval "$(azd env get-values)"
TOKEN=$(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)   # Azure CLI is pre-authorized (dev)
```

## 1. How the assistant works

```
portal ──SSE──▶ API Management /api/v1/agent/* ──▶ device analyst (Container Apps, Python, Agent Framework)
                                                        │  ├─▶ gpt-6-luna (Foundry, EU data zone, Entra auth)
                                                        │  └─▶ read-only tools ─▶ API Management ─▶ device API ─▶ PostgreSQL
                                                        └─▶ conversations in Azure Managed Redis (1 h, owner only)
```

- The agent is a small service on Container Apps, next to the other services. The model is a `gpt-6-luna` deployment of the environment's Foundry account (DataZoneStandard: processed in the EU; keys disabled, the agent uses its managed identity).
- Its **tools** call the platform API **with the user's own token**: the assistant sees exactly what the user may see, through the same gateway, role checks and rate limits as the portal. All tools are read-only.
- Answers are **streamed**: the panel shows the data being consulted, then the text as it is written, then the sources.

## 2. Ask about the fleet

1. Open `$WEB_URL`, sign in (a Reader is enough) and choose **Assistant** in the header. The panel opens on the right and stays open while you navigate.
2. Ask: *Compare humidity between the Paris and Lyon sites this week.*
   - *Summarising humidity…* appears while the agent reads a fleet summary (one database query over hourly rollups, whatever the number of devices).
   - The answer gives the averages, ranges and periods per site; **Sources** links to the site pages.
3. Ask: *Which devices of the Lyon site have been silent since yesterday?* The agent checks the reporting status (a device is silent after 15 minutes without data). Normally none is silent: the answer says so instead of inventing devices.
4. Ask: *Is the battery of any device likely to run out soon?* The agent compares battery readings across the fleet over a period and explains its reasoning with figures.

## 3. Ask about a device, then follow up

1. Choose **Devices**, site `lyon-01`, and open `lyon-01-cayenne-001` (*Lyon cold room*). The panel footer now says *About lyon-01-cayenne-001 unless you name another device*.
2. Ask: *How did the temperature evolve over the last 7 days? Anything unusual?* The agent reads the device's 7-day series (activity *Reading the temperature history of lyon-01-cayenne-001*) and describes the trend with figures.
3. Choose the source link `lyon-01-cayenne-001 · <dates>`: the device page opens on the **Custom** period the agent used, with the temperature chart. The panel keeps the conversation.
4. Follow up: *And its humidity?* The assistant keeps the context (same device, same kind of question).
5. Device names work too: from any page ask *What is the latest temperature of the Lyon cold room?*

## 4. Language

Ask in French: *Quels capteurs de Lyon sont silencieux ?* The answer is in French. Units and ids stay as the platform defines them.

## 5. Guardrails

- **Read-only**: ask *Delete the temperature threshold of paris-01-ruuvi5-001*. The assistant declines, explains it can only analyse data and points to the device page. No tool is called (nothing appears under the question). Even for an Editor, the agent has no tool that could change data.
- **Unknown data**: ask *What is the temperature of device does-not-exist?* It says it cannot find the device and suggests searching by name or site.
- **Permissions**: a user without the *Query device data* role does not see the **Assistant** button, and the API answers 403.
- **Private, expiring conversations**: a conversation belongs to the user who started it (others get 404) and expires one hour after its last message. **New conversation** starts over.
- **Rate limit**: 20 messages per minute per user. From a terminal:

  ```bash
  ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/agent/conversations" | python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])')
  for i in $(seq 1 21); do
    curl -s -o /dev/null -w "%{http_code} " -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      "$API_BASE_URL/agent/conversations/$ID/messages" -d '{"content": "Reply with just: ok"}'
  done; echo
  ```

  Twenty `200`, then `429` (with a `Retry-After` header).

## 6. Behind the scenes

- **One trace per question** (Application Insights, Logs): the agent run, each model call with its token usage, each tool call, and the platform API requests they caused (gateway, device API, database):

  ```kusto
  let turns = dependencies
  | where timestamp > ago(1h) and cloud_RoleName == "sdp-device-analyst" and name startswith "invoke_agent"
  | project timestamp, operation_Id, seconds = round(duration / 1000, 1),
            inputTokens = toint(customDimensions["gen_ai.usage.input_tokens"]),
            outputTokens = toint(customDimensions["gen_ai.usage.output_tokens"]);
  turns
  | join kind=leftouter (
      dependencies
      | where timestamp > ago(1h) and cloud_RoleName == "sdp-device-analyst" and name startswith "execute_tool"
      | summarize tools = make_list(substring(name, 13)) by operation_Id) on operation_Id
  | project-away operation_Id1
  | order by timestamp desc
  ```

  Open one `operation_Id` in **Transaction search** to see the end-to-end timeline across the agent, API Management and the device API. With content capture enabled, the `chat gpt-6-luna` spans also hold the prompts and answers.
- **Foundry project**: the environment's Foundry project (`azd env get-value AZURE_AI_PROJECT_NAME`) holds the model deployment and is connected to the same Application Insights. Model usage and quota are on the deployment's page.
- **Evaluation**: the agent comes with an evaluation set of 17 questions (the PRD examples, follow-ups, device context, refusals, unknown device, French), scored for groundedness, relevance and tool-call accuracy by Azure AI Evaluation, plus objective checks including the time to the first word. See [agents/device-analyst/README.md](../../agents/device-analyst/README.md#evaluation) for the latest scores; to run it (about 5 minutes):

  ```bash
  cd agents/device-analyst && ../../scripts/agent-env.sh && uv run --group eval python evals/run_eval.py
  ```

## After the demo

If you enabled content capture, turn it off again (prompts and answers can contain business data):

```bash
azd env set AGENT_CAPTURE_CONTENT "" && azd provision
```

Conversations expire on their own after an hour.
