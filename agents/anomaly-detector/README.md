# Anomaly detector (M7), site digest (M8) and predictive maintenance (M9)

Scheduled Container Apps job (every 5 minutes) that finds unusual sensor readings on the monitored sites and records
them in `sdp.anomalies` ([PRD-007](../../docs/prd/PRD-007-m7-anomaly-detection.md),
[ADR-0011](../../docs/adr/ADR-0011-model-based-analysis.md),
[design](../../openspec/changes/archive/2026-10-30-m7-anomaly-detection/design.md)).

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

### Tuning

The defaults come from dry runs over 7 days of `sdp-dev` (`paris-01`, 22–29 Oct 2026):

| Deviation | Episodes / 7 days | Per day | Main metrics |
|---|---|---|---|
| 3 | 120 | 17.1 | CO₂ jumps (noise) |
| 3.5 | 54 | 7.7 | CO₂, pressure |
| **4** | **20** | **2.9** | pressure, CO₂, the injected temperature anomalies |
| 4.5 | 17 | 2.4 | pressure |

At deviation 4, classified by `decision-1`:

| Confidence | Confirmed | To review | Confirmed per day |
|---|---|---|---|
| 0.6 | 16 | 4 | 2.3 |
| **0.7** | **12** | **8** | **1.7** |
| 0.8 | 7 | 13 | 1.0 |
| 0.9 | 6 | 14 | 0.9 |

Re-run them after changing the detection or the data:

```bash
uv run anomaly-detector --dry-run --days 7 --deviation 4 --output /tmp/episodes.jsonl            # no model call
uv run anomaly-detector --dry-run --days 7 --deviation 4 --classify --output /tmp/episodes.jsonl # decision model, no write
```

The episodes file holds every episode with its state and, with `--classify`, its probabilities: the confidence can be
chosen from it without calling the model again.

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

## Site digests (M8)

The same project has a second entry point, `site-digest`, run by its own scheduled job (every 5 minutes, same image and
identity) ([PRD-008](../../docs/prd/PRD-008-m8-site-digest.md),
[design](../../openspec/changes/archive/2026-10-30-m8-site-digest/design.md)):

| Step | Who | What |
|---|---|---|
| When | the job | each configured site once a day after 06:00 UTC, and any digest an Editor requested (`sdp.site_digest_requests`) |
| Figures | code | devices reporting and silent, metric averages (last 24 h vs the 7 days before), alerts, anomalies, rejected messages, certificates expiring within 30 days |
| Brief | `gpt-6-luna` | at most six sentences from the figures only; every number is checked against the figures |

| Variable | azd setting | Default |
|---|---|---|
| `SDP_DIGEST_SITES` | `DIGEST_SITES` | every site |
| `SDP_DIGEST_METRICS` | `DIGEST_METRICS` | `temperature,humidity,pressure,co2,illuminance,battery_voltage,battery_level` |
| `SDP_MAINTENANCE_ENABLED`, `SDP_MAINTENANCE_SITES` | `MAINTENANCE_ENABLED`, `MAINTENANCE_SITES` | the figures of the sites the maintenance job assesses include their queue (open and planned counts, the 5 most likely devices) |
| — | `DIGEST_ENABLED` | `true`; `false` removes the job |

```bash
uv run site-digest --dry-run --site paris-01             # figures and brief for now, nothing written
uv run site-digest --dry-run --site paris-01 --no-brief  # figures only, no model call
```

## Predictive maintenance (M9)

A third entry point, `maintenance`, run by the job `caj-upkeep-<token>` (hourly, 10-minute timeout, same image and
identity) ([PRD-009](../../docs/prd/PRD-009-m9-maintenance.md),
[design](../../openspec/changes/archive/2026-10-31-m9-maintenance/design.md)). One run assesses every device of the configured sites,
then updates the queue (`sdp.device_maintenance`, `sdp.maintenance_items`):

| Step | Who | What |
|---|---|---|
| Battery forecast | code (`battery.py`) | hourly battery averages of 14 days, from the last sudden change (> 0.1 V or 5 points: a replacement); weighted trend (half-life 3 days) → level, trend per day, days left before 2.5 V or 10 % |
| Signals | SQL (`data.py`) | messages, median interval and longest gap over the last 24 hours and the 7 days before, minutes since the last message, rejected messages |
| Probability | `Microsoft-Decision-1` | will the device need an on-site intervention within 7 days? (one question per device and run) |
| Queue | code (`lifecycle.py`) | from `SDP_MAINTENANCE_CONFIDENCE`: open an item with its reasons, update it, or resolve it when the probability falls; no new item within 7 days of a visit marked done |
| Work order | `gpt-6-luna` | at most four sentences for a new item (refreshed after 24 hours); every number is checked against the input |

| Variable | azd setting | Default |
|---|---|---|
| `SDP_MAINTENANCE_SITES` | `MAINTENANCE_SITES` | every site |
| `SDP_MAINTENANCE_CONFIDENCE` | `MAINTENANCE_CONFIDENCE` | `0.4` (tuned below) |
| — | `MAINTENANCE_ENABLED` | `true`; `false` removes the job (data kept) |

```bash
uv run maintenance --dry-run --site paris-01 --no-models  # forecasts and signals only, nothing written
uv run maintenance --dry-run --site paris-01              # with the probabilities and work orders, nothing written
```

### Tuning the maintenance confidence

`MAINTENANCE_CONFIDENCE=0.4` comes from live calls of the decision model on real `sdp-dev` signals and forecasts (31 Oct
2026), with the question's explicit criteria (battery cut-off within 7 days; silent for more than an hour; gaps more
than ten times the usual interval; 20 or more rejected messages in 24 hours):

| Case (live, Microsoft-Decision-1) | Probability |
|---|---|
| Aging battery, 3.5 / 5 days left | 0.98 / 0.99 |
| Aging battery, 9 / 12 days left | 0.27 / 0.15 |
| Healthy battery (300 days), no trend yet | 0.01 / 0.03 |
| No battery: healthy signals, silent 30 min | 0.01 / 0.01 |
| No battery: silent 6 h | 0.82 |
| No battery: gaps 20 times the usual interval | 0.50 |
| No battery: 40 / 8 rejected messages in 24 h | 0.99 / 0.01 |

0.4 queues the aging batteries and the failing devices with a margin, and none of the healthy cases.

A run is one Application Insights request `sdp.maintenance.run` (role `sdp-maintenance`) with its counts, and child
spans `sdp.maintenance.decide` (probability) and `sdp.maintenance.work_order` (token usage, unsupported numbers).

The image is built in Azure Container Registry by `scripts/build-anomaly-detector-image.sh` from the post-provision
hook (not by `azd deploy`); it points the three jobs at it.
