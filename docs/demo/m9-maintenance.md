# Demo guide — M9: Battery forecast and maintenance queue

> **Draft:** written with the implementation; the figures and timings below are checked against `sdp-dev` by task 6.3 of the [change](../../openspec/changes/m9-maintenance/tasks.md).

Shows which devices need a visit before they stop: the platform forecasts each battery and measures how each device reports, `Microsoft-Decision-1` gives the probability of a visit within 7 days, and `gpt-6-luna` writes a short work order for the devices that enter the queue ([PRD-009](../prd/PRD-009-m9-maintenance.md), [ADR-0011](../adr/ADR-0011-model-based-analysis.md)). Duration: about 15 minutes.

## Before the demo

The environment must have M1–M9 deployed and the simulator running:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
eval "$(azd env get-values)"
```

The maintenance job (`caj-upkeep-<token>`) runs every hour and assesses every site of `MAINTENANCE_SITES` (default all). The simulator drains the battery of one device per site in about two weeks, then replaces it (`SIMULATOR_AGING_DEVICES`, default `<site>-ruuvi5-003`): forecasts are meaningful one day after the simulator is deployed, and an aging device enters the queue when its battery has a few days left.

Requirements on the presenter's machine: `az`, `azd`, Python 3 with [uv](https://docs.astral.sh/uv/) (for the dry run).

## 1. Why a maintenance queue?

A field team visits sites on a schedule; a device whose battery runs out between two visits is silent for days. The queue says, every hour, which devices are likely to need a visit within the next 7 days, and why:

| Part | Who |
|---|---|
| Battery forecast: level, trend per day, days left before the cut-off (2.5 V or 10 %) | computed by the platform, from the last battery replacement |
| Reporting signals: gaps, silence, rejected messages | computed by the platform |
| Probability of a visit within 7 days | `Microsoft-Decision-1` |
| Work order: at most four sentences | written by `gpt-6-luna` **from the figures only** |

## 2. Read the queue

1. Sign in to the portal and open **Maintenance**.
2. The table lists the devices most likely to need a visit first: probability, battery days left, the reasons (each a computed figure that crossed a rule, e.g. *Battery runs out in 4.6 days*), the work order and the status.
3. Filter by site (`lyon-01`) and by status (*Open*, *Planned*, *Done*, *Resolved*).
4. Open an aging device (`<site>-ruuvi5-003`): its **Battery and maintenance** card shows the battery level, the trend per day, the days left, the probability and when it was assessed, with its queue item.
5. Open a healthy device: the card shows a stable battery, no days left and a low probability, and no item.

A site the job does not assess says *Maintenance is not assessed for this site.*; with `MAINTENANCE_ENABLED=false` the page and the card are not shown at all.

## 3. Plan and close a visit

1. As an **Editor**, select **Planned** on a queue item, add a comment (*Visit on Monday*) and confirm: the item shows *Planned*, by you, with the comment.
2. After the visit, select **Done**: the item leaves the queue (filter *Done* to see it). The device does not re-enter the queue for 7 days.
3. When the battery is replaced (the simulator does it at the end of the aging cycle), the forecast restarts from the new battery: the probability falls and the platform resolves any item still open (*Resolved*, without a name).

A **Reader** has no Planned or Done action, and the API refuses with 403. A closed item cannot be changed (409, *This item is already closed.*).

## 4. Ask the assistant, read the digest

- Open the **Assistant** and ask *Which devices need a visit this week?* It answers from the queue, with the probabilities, the days left and the work orders, and cites the devices.
- On **Sites → lyon-01**, the next **Digest** (or a regenerated one) mentions the maintenance queue: how many devices are in it and the most likely ones.

## 5. Behind the scenes

**Dry run.** Assess a site now, without writing anything:

```bash
scripts/anomaly-env.sh                                   # .env with your Entra identity
cd agents/anomaly-detector
uv run maintenance --dry-run --site lyon-01 --no-models  # forecasts and signals only
uv run maintenance --dry-run --site lyon-01              # with the probabilities and work orders
```

**Traces.** In Application Insights (Transaction search, role `sdp-maintenance`), a request `sdp.maintenance.run` counts the devices assessed and the items opened, updated and resolved; its children are `sdp.maintenance.decide` (one per device, with the probability) and `sdp.maintenance.work_order` (token usage, and `sdp.maintenance.unsupported_numbers`, which should be 0).

**Failures are not hidden.** If the decision model fails, the device keeps its previous assessment, with its time; if the work order fails, the item is shown without one and a later run adds it.

## After the demo

Nothing to clean up: closed items (*done*, *resolved*) are kept 30 days, like the measurements.
