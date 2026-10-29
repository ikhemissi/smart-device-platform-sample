# PRD-007 — M7: Anomaly detection

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M7 |
| Depends on | M3 (measurement history), M6 (AI models in the environment) |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Notice **unusual readings without anyone setting a threshold**, and tell the people looking at the platform whether an anomaly is most likely a **real event** at the site (a cold room warming up), a **sensor fault** (a stuck or erratic sensor) or an **expected pattern** — with how confident the platform is and a short explanation in plain language.

M5 alerting covers what users already know to watch for; anomaly detection covers what nobody configured.

## 2. Example situations

- The temperature of a cold room at Paris rises far above its usual daily profile for twenty minutes → *real event, 91 % — "lyon-01-cayenne-001 reached 14.2 °C at 10:40, 6 °C above its usual level at this time; its neighbours stayed normal."*
- A humidity sensor reports exactly the same value for two hours while it usually varies → *sensor fault, 87 %.*
- A device's temperature jumps briefly, as it does every morning when a door is opened → *expected pattern* (not shown as an anomaly to act on).
- A reading is unusual but the evidence is thin → *cannot tell*: listed for a person to review.

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M7-US1 | Data Viewer | to see the anomalies detected on a device on its page, with what was unusual, when, the platform's classification and confidence, and a short explanation | I understand what happened without analysing the chart myself |
| M7-US2 | Data Viewer | a list of recent anomalies across the monitored sites, filtered by site and by status | I can check the fleet at a glance |
| M7-US3 | Data Editor | to mark an anomaly as reviewed (and say whether the classification was right) | the list shows what still needs attention, and the platform's accuracy can be measured |
| M7-US4 | Data Viewer | anomalies the platform is unsure about to be clearly separated from confident ones | I do not act on doubtful findings, and I know which ones to look at |
| M7-US5 | Data Viewer | to ask the AI assistant about anomalies ("anything unusual at paris-01 today?") | I get the answer in the conversation |
| M7-US6 | Platform operator | to choose which sites are analysed | the analysis (and its cost) covers the sites that matter |

## 4. Acceptance criteria

1. On the analysed sites, an unusual reading appears in the portal **within 10 minutes**, with the metric, the period, the value compared with the device's usual values, the classification (real event, sensor fault, expected pattern, cannot tell) and its confidence.
2. Anomalies classified with a confidence below the configured level are shown as **"to review"**, separately from confident ones; expected patterns are recorded but not presented as anomalies to act on.
3. Confident anomalies have an explanation of at most a few sentences, based only on the device's data, naming the device, the metric, the values and the time.
4. With the demo fleet running normally, anomalies appear **now and then** (typically a few per day on the analysed sites), not continuously.
5. Editors can mark an anomaly as reviewed with their verdict; Readers cannot.
6. The AI assistant can list and explain recent anomalies, within the user's permissions.
7. If the decision model is unavailable in an environment, the anomaly features are switched off without affecting the rest of the platform.

## 5. Out of scope

- Email or other notifications for anomalies (M5 alerting stays the notification channel).
- Training or fine-tuning models on the platform's data.
- Anomalies across several devices at once (e.g. a whole site) — single device and metric only.
- Automatic actions on devices or thresholds.
