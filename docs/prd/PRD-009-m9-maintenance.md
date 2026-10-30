# PRD-009 — M9: Battery forecast and maintenance queue

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M9 |
| Depends on | M6 (rollups, AI models), M7 (decision model), M8 (site digest) |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Tell the field team **which devices need a visit this week, and why**, before they stop reporting: a forecast of each device's battery, a probability that the device needs an intervention within 7 days, and a maintenance queue sorted by that probability, each item with a short work order.

## 2. Example situations

- *"lyon-01-ruuvi5-002: battery 2.62 V, falling 0.05 V a day; it reaches 2.5 V in about 3 days. Intervention within 7 days: 91 %. Work order: replace the battery of lyon-01-ruuvi5-002 before Monday."*
- A device has reported every 30 seconds for weeks but now leaves gaps of several minutes and sends corrupted messages → it rises in the queue although its battery is fine.
- A battery was replaced yesterday → its forecast restarts from the new battery and the device leaves the queue.
- An Editor plans the visit (*planned*), then marks it *done* after the visit.

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M9-US1 | Field team lead (Data Viewer) | a maintenance queue of the devices likely to need an intervention within 7 days, most likely first, with the reason and a work order | I plan visits where they are most needed |
| M9-US2 | Data Viewer | each device page to show its battery level, its trend and the estimated days left | I understand a device's state at a glance |
| M9-US3 | Data Editor | to mark a queued device as planned or done, with a comment | the queue shows what is already handled |
| M9-US4 | Site manager | the site digest to say how many devices need maintenance | the daily brief covers it |
| M9-US5 | Data Viewer | to ask the AI assistant which devices need maintenance | I get the answer in the conversation |
| M9-US6 | Presenter | some simulated devices to age visibly | the demo shows the queue working |

## 4. Acceptance criteria

1. Every hour, each device of the configured sites has a battery forecast (level, trend, estimated days left before its cut-off) when it reports a battery, and a probability of needing an intervention within 7 days.
2. The forecast follows the recent trend and restarts after a battery replacement (a jump up of the battery level).
3. The queue lists the devices whose probability is at or above the configured level (default 50 %), most likely first, each with the main reasons (figures computed by the platform) and a work order of a few sentences written from them.
4. Editors can mark an item planned or done with a comment; Readers cannot. A device marked done is not queued again for 7 days.
5. Device pages show the battery forecast; the site digest gives the number of devices queued and the most urgent ones.
6. The AI assistant can list the queue within the user's permissions.
7. With the demo fleet, at least one device per site is visibly aging and reaches the queue within two weeks; simulator restarts do not reset batteries.
8. Maintenance can be switched off per environment without affecting the rest of the platform.

## 5. Out of scope

- Notifications (email) about the queue; scheduling visits or assigning technicians.
- Automatic actions on devices.
- Forecasts of other components than the battery (signal, sensors) beyond the reporting signals used for the probability.
