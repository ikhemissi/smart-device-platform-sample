# Device analyst (M6)

Conversational assistant of the Smart Device Platform: answers questions about devices, measurements and alerts with
data read through the platform API **as the signed-in user**, and never modifies anything. Python 3.13,
[Microsoft Agent Framework](https://github.com/microsoft/agent-framework) over the `gpt-6-luna` deployment of the
environment's Foundry account, FastAPI with Server-Sent Events, conversations in Azure Managed Redis
([ADR-0010](../../docs/adr/ADR-0010-ai-agent.md), spec `device-analyst-agent`).

```
portal ──SSE──▶ APIM /api/v1/agent/* ──▶ device-analyst (this service) ──▶ Foundry model (Responses API)
                                              │  tools, caller's token
                                              └──▶ APIM /api/v1/{sites,fleet,devices,alerts} ──▶ device-api
```

## API

| Operation | Description |
|---|---|
| `POST /api/v1/agent/conversations` | New conversation (201, `{id, createdAt, messages: []}`) |
| `POST /api/v1/agent/conversations/{id}/messages` | `{content, context?: {deviceId}}` → `text/event-stream`: `tool` (`{name, arguments}`), `delta` (`{text}`), then `done` (`{conversationId, citations}`) or `error` (`{title, detail}`) |
| `GET /api/v1/agent/conversations/{id}` | Transcript: user questions (with their device context) and answers with citations |
| `DELETE /api/v1/agent/conversations/{id}` | 204 |

Every operation requires an Entra access token for the platform API with `DeviceData.Read` (or `Write`); errors are
problem details. A conversation belongs to its creator (others get 404), expires one hour after its last message and
keeps its 50 latest messages; a second message while one is being answered gets 409. Citations are
`{kind: "device", deviceId, metrics?, from?, to?}` or `{kind: "site", siteId}`. A user may send 20 messages per minute
(sliding window in Redis, 429 with `Retry-After`); API Management applies the same limit as an outer guard and streams
answers without buffering (`openapi.json` is imported by the gateway; a test keeps it in line with the routes).

Tools (all read-only): `list_sites`, `fleet_status`, `metric_summary`, `search_devices`, `get_device`,
`get_latest_measurements`, `get_series`, `list_alerts`, `get_thresholds` — see `src/device_analyst/tools.py`.

## Configuration

Environment variables (set by Terraform in Azure, `infra/modules/apps`):

| Variable | Description |
|---|---|
| `AZURE_OPENAI_ENDPOINT`, `FOUNDRY_MODEL_DEPLOYMENT_NAME` | Foundry account endpoint and model deployment (default `gpt-6-luna`) |
| `SDP_API_BASE_URL` | Platform API base URL at the gateway (`https://<apim>.azure-api.net/api/v1`) |
| `SDP_ENTRA_TENANT_ID`, `SDP_API_AUDIENCES` | Token validation: tenant, accepted audiences (comma-separated) |
| `SDP_REDIS_HOST`, `SDP_REDIS_PORT` | Azure Managed Redis (Entra authentication) |
| `AZURE_CLIENT_ID` | Workload identity (Azure only) |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | Trace export to Application Insights (Entra authentication) |
| `SDP_AGENT_CAPTURE_CONTENT` | `true` records prompts, answers and tool results in traces (`azd env set AGENT_CAPTURE_CONTENT true` for demos) |
| `SDP_AGENT_MAX_TOOL_CALLS`, `SDP_AGENT_TURN_TIMEOUT_SECONDS` | Limits per turn (10, 110 s) |
| `SDP_AGENT_MESSAGES_PER_MINUTE` | Messages per user and minute (20) |
| `SDP_AGENT_CONVERSATION_TTL_SECONDS`, `SDP_AGENT_MAX_MESSAGES` | Conversation expiry and size (3600, 50) |

## Run locally

Prerequisites: [uv](https://docs.astral.sh/uv/), `az login` with an account that deployed the environment (Terraform
grants the deployer `Foundry User` on the AI account and a Redis access policy), and a user token for the platform API.

```bash
cd agents/device-analyst
uv sync
../../scripts/agent-env.sh            # writes .env from the current azd environment
uv run device-analyst                 # http://localhost:8080
```

Ask a question with your own token:

```bash
API_CLIENT_ID=$(azd env get-value API_CLIENT_ID)
TOKEN=$(az account get-access-token --scope "$API_CLIENT_ID/.default" --query accessToken -o tsv)
ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/agent/conversations | jq -r .id)
curl -N -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  localhost:8080/api/v1/agent/conversations/$ID/messages -d '{"content": "Which devices are reporting at paris-01?"}'
```

**Debug in VS Code:** the workspace's `.vscode/launch.json` attaches `debugpy` to the service started by
`.vscode/tasks.json` (*Debug Local Agent/Workflow in Terminal*). The *HTTP Server* variant also opens the Foundry
Toolkit Agent Inspector; its chat panel speaks Foundry's hosted-agent protocol, which this service does not expose
(it runs on Container Apps with its own conversation API, ADR-0010), so use the API above to chat while debugging.

**Local traces:** set `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` (and optionally
`SDP_AGENT_CAPTURE_CONTENT=true`) in `.env` and open the Foundry Toolkit trace viewer.

## Tests

```bash
uv run pytest          # unit and API tests (fakeredis, mocked platform API, scripted model)
uv run ruff check src tests && uv run ruff format --check src tests
```

## Deploy

`azd deploy device-analyst` builds the image in Azure Container Registry and updates the `ca-analyst-*` container app.
In Application Insights, each message is one trace (cloud role `sdp-device-analyst`): `invoke_agent device-analyst`,
`chat gpt-6-luna` model calls with token usage, `execute_tool …` spans and the platform API requests they cause.
