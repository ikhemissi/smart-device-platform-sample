# Demo guide — M7: Anomaly detection

Shows how the platform finds unusual readings without configured thresholds, lets a decision model classify them with a confidence, explains the confident ones with an LLM, and keeps every decision reviewable ([PRD-007](../prd/PRD-007-m7-anomaly-detection.md), [ADR-0011](../adr/ADR-0011-model-based-analysis.md)). Duration: about 30 minutes, most of it waiting for the anomalies you inject.

## Before the demo

The environment must have M1–M7 deployed, the simulator running, and at least 3 days of history on the analysed site (`paris-01` by default; 30 days with `scripts/backfill-history.sh` is better):

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
eval "$(azd env get-values)"
```

`azd provision` deploys `Microsoft-Decision-1` (deployment `decision-1`) and the anomaly detector job, and builds the detector image. The portal shows **Anomalies** in the navigation once deployed (`anomaliesEnabled` in `config.json`).

Requirements on the presenter's machine: `az`, `azd`, Python 3 with [uv](https://docs.astral.sh/uv/) (for the dry run).

**Inject the anomalies 30 minutes before you need them.** Each is detected and classified within 5–10 minutes of its start, and the injection restarts the simulator. The two stories use different devices and do not overlap in time: otherwise the warming devices of story 2 would make the jump of story 1 look site-wide.

```bash
# Story 1 — a faulty sensor: after 1 minute, one device jumps at once to 35 °C and stays there for 10 minutes.
# Story 2 — a real event: after 15 minutes, three devices warm up together to 32 °C over 10 minutes, stay there
#           10 minutes, then cool down over 10 minutes.
az containerapp update -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR_APP_NAME" --set-env-vars \
  "SDP_SIM_ANOMALIES=paris-01-ruuvi5-001:temperature=35:10m:1m,paris-01-ruuvi3-001:temperature=32~10m:10m:15m,paris-01-ruuvi3-002:temperature=32~10m:10m:15m,paris-01-ruuvi3-003:temperature=32~10m:10m:15m"
```

The `~10m` form ramps the value with the device's normal noise (see the [simulator README](../../services/device-simulator/README.md#anomalies-on-demand-during-a-demo)). If the command reports a timeout, check that the setting was applied before running it again:

```bash
az containerapp show -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR_APP_NAME" \
  --query "properties.template.containers[0].env[?name=='SDP_SIM_ANOMALIES'].value" -o tsv
```

> An anomaly of a device and metric that ended less than 15 minutes earlier is **extended**, not recreated. Use devices without a recent anomaly, or wait 30 minutes between rehearsals.

## 1. Why not thresholds?

M5 alerts on fixed thresholds per device: precise, but someone must set them, and they miss what nobody anticipated. M7 compares each device with **itself**:

| Step | Who | What |
|---|---|---|
| Select | statistics | the last 15 minutes against the device's usual value for that hour over the last 7 days: far from usual, stuck, or a jump |
| Decide | `Microsoft-Decision-1` | real event, sensor fault, expected pattern or cannot tell, with a probability for each |
| Explain | `gpt-6-luna` | at most four sentences, from the decision's data only, for confirmed anomalies |

The confidence threshold (0.7) turns the probabilities into a status: **confirmed**, **to review** (the model was unsure) or **dismissed** (expected pattern).

## 2. The Anomalies page

1. Open the portal and sign in as an **Editor**. Select **Anomalies**.
2. The default list shows the anomalies to act on (**confirmed** and **to review**) of the analysed sites, most recent first.
3. Point at **story 1**: `paris-01-ruuvi5-001`, "Sensor fault · 98 %". The temperature jumped to 35 °C at once and held a constant value while the other devices of the site stayed normal. The explanation says so in a few sentences.
4. Point at **story 2**: the three `ruuvi3` devices, "Real event · 72–77 %". The same gradual warm-up on three devices at once looks like the room really changed. One of them may be **To review**: the model was less sure, and the platform asks a person instead of guessing.
5. Open **Status** and add **Dismissed**: expected patterns, recorded but not listed by default.

What decides between the two stories is the context the model receives: the readings' shape (constant value or gradual change with noise) and the same metric on the **other devices of the site** over the same minutes.

## 3. A device's anomalies

1. Select the link of the `paris-01-ruuvi3-001` anomaly. The device page opens over the anomaly's period.
2. The chart shows the warm-up; the **Anomalies** card lists the anomaly with its period, observed and usual values, classification, confidence and explanation.
3. Open a device of `lyon-01`: the card says anomalies are not analysed for this site (`ANOMALY_SITES`).

## 4. Review

1. Back on **Anomalies**, select **Review** on an anomaly to review.
2. Choose **Classification incorrect** (or correct), add a comment ("Door left open during a delivery"), and save.
3. The anomaly leaves the default list. Select the **Reviewed** status: it shows the verdict, your name, the time and the comment.

Reviews never change the automated decision: they are kept next to it, as labelled data to recalibrate the threshold later. A **Reader** sees no Review button, and the API refuses with 403.

## 5. Ask the assistant

Open the **Assistant** and ask:

- *Anything unusual at paris-01 today?* It lists the confirmed anomalies and the ones to review, with classifications and confidences as recorded, devices and periods, and links to the devices as sources.
- *Why is paris-01-ruuvi5-001 a sensor fault?* It answers from the recorded explanation and values, without reclassifying.

## 6. Behind the scenes

**Every decision is recorded.** In Application Insights (Transaction search, role `sdp-anomaly-detector`), open a request `sdp.anomaly.cycle` from the injection time:

- `sdp.anomaly.classify` holds the probability of each class (`sdp.anomaly.p.real_event`, …) and calls `/providers/microsoft/v1/systemone` (about 0.2–0.6 s);
- `sdp.anomaly.explain` holds the token usage and calls the Responses API (about 2 s);
- the request's properties count devices, candidates, anomalies created and extended, and decisions by status.

**The thresholds come from the data.** A dry run replays the last days with the live logic, writes nothing and, without `--classify`, calls no model:

```bash
scripts/anomaly-env.sh                       # .env with your Entra identity
cd agents/anomaly-detector
uv run anomaly-detector --dry-run --days 7 --deviation 3.5
uv run anomaly-detector --dry-run --days 7 --deviation 4
```

Each run prints candidates and episodes per day; the defaults (deviation 4, confidence 0.7) give about 3 candidates and 1.7 confirmed anomalies a day on `paris-01` ([tuning](../../agents/anomaly-detector/README.md#tuning)).

**Passwordless and least privilege.** The job's identity holds *Foundry User* on the Foundry account (no keys: local authentication is disabled) and the PostgreSQL role `sdp_insights`, which writes anomalies only. `ANOMALY_DETECTION_ENABLED=false` removes the job and the decision deployment; the portal and the assistant hide the feature.

## After the demo

Remove the injected anomalies (this restarts the simulator):

```bash
az containerapp update -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR_APP_NAME" --remove-env-vars SDP_SIM_ANOMALIES
```

The recorded anomalies stay for 30 days, like the measurements.
