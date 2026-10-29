# Anomaly detector (M7)

Scheduled Container Apps job (every 5 minutes) that finds unusual sensor readings on the monitored sites and records
them in `sdp.anomalies` ([PRD-007](../../docs/prd/PRD-007-m7-anomaly-detection.md),
[ADR-0011](../../docs/adr/ADR-0011-model-based-analysis.md),
[design](../../openspec/changes/m7-anomaly-detection/design.md)).

| Step | Who | What |
|---|---|---|
| Select | statistics | readings far from the device's usual value at that hour, flat lines, jumps |
| Decide | `Microsoft-Decision-1` | real event, sensor fault, expected pattern or cannot tell, with probabilities |
| Explain | `gpt-6-luna` | a short explanation of confirmed anomalies |

## Settings

| Variable | azd setting | Default |
|---|---|---|
| `SDP_ANOMALY_SITES` | `ANOMALY_SITES` | `paris-01` |
| `SDP_ANOMALY_METRICS` | `ANOMALY_METRICS` | `temperature,humidity,pressure,co2,illuminance` |
| `SDP_ANOMALY_DEVIATION` | `ANOMALY_DEVIATION` | `4` |
| `SDP_ANOMALY_CONFIDENCE` | `ANOMALY_CONFIDENCE` | `0.7` |
| — | `ANOMALY_DETECTION_ENABLED` | `true`; `false` removes the job and the decision model deployment |
| — | `DECISION_MODEL_CAPACITY` | `30` (each unit: 1 request and 1,000 tokens per minute) |

The database, endpoint and identity variables are set by Terraform (`infra/modules/apps`).

## Local development

```bash
cd agents/anomaly-detector
uv sync
uv run ruff check src tests && uv run ruff format --check src tests
uv run pytest                      # unit tests
../../scripts/anomaly-env.sh       # .env from the azd environment (your Entra identity)
uv run pytest -m live              # checks against the environment
uv run anomaly-detector            # one cycle
```

The image is built in Azure Container Registry by `scripts/build-anomaly-detector-image.sh` from the post-provision
hook (not by `azd deploy`).
