# Design

## Context

M9's battery forecast ([archived design D2](../archive/2026-10-31-m9-maintenance/design.md)) fits a weighted line to the hourly averages of the last 14 days, starting after the last step larger than 0.1 V (5 points for a level). On 31 October in `sdp-dev`, the hourly series showed:
- hourly noise of about 0.0003 V (0.045 points for a level);
- the simulator change of 30 October 22:00 UTC (D11) left steps of 0.01–0.06 V on most devices, spread over two hourly averages, so not detected. The forecast mixed them into the trend: lyon-01-ruuvi3-001 had 42 days left (about 240 in reality), paris-01-ruuvi5-001 a level of 2.921 V (2.887 V);
- paris-01-ruuvi5-003 went from flat to -0.035 V/day with a 0.023 V step, and was forecast at -0.0035 V/day (128 days left).

The digest compares each configured metric's average over the last 24 hours with the previous 7 days, battery metrics included by default; the same digest now carries the maintenance queue. Work orders repeat the reason records ("Reason 1: … below the 7.0 days threshold").

## Goals / Non-Goals

**Goals:** a forecast that follows the battery's current behaviour after any lasting change; digests without battery averages by default; work orders a technician can act on.

**Non-Goals:** temperature compensation of the voltage, several cut-offs per battery chemistry, changing the decision model's criteria or the queue lifecycle.

## Decisions

### D1. Change point: best two-line split, repeated
Within the segment after the last sudden change, the forecast looks for a change point:
- among the splits leaving at least 12 hours (`MIN_TREND_SPAN`) on each side, take the one whose two ordinary least-squares lines (one per side) have the smallest total squared error;
- it is a change point when, at the latest point, the recent side's line differs from the line of the whole segment by more than the kind's `departure` (0.01 V, 1.5 points for a level): ignoring the change would misstate the level now;
- the segment restarts there, and the search repeats on the rest until no change point remains (the last change wins).

Sums are accumulated once per segment (prefix sums), so a split costs O(1): 336 hourly points per device stay cheap.

Why: one rule covers the end-of-life knee, small steps and a step spread over two hours, without a model. Choosing the split first and testing it second keeps a skewed split (one that cuts through a step) from qualifying just because its recent line is skewed. Comparing with the whole segment's line, rather than extrapolating the earlier side, keeps a short earlier side's slope error from being magnified over days. The 12-hour minimum on each side matches the minimum span for a trend: a segment always covers at least 12 hours. 0.01 V is about 30 times the hourly noise in `sdp-dev` and well under the smallest decline that matters (an aging battery loses 0.035 V a day). For levels, 1.5 points stays above the rounding of a level reported in whole percent: in 330 simulated whole-percent series (0.05–4 points a day, 3–14 days, with noise), none was split.

On 31 October's data, every healthy device's forecast went back to about -0.0015 V/day (the simulator's rate), paris-01-ruuvi5-003 to -0.035 V/day (11.7 days left), and the aging devices of lyon-01 and marseille-01 were unchanged.

Alternatives: a lower sudden-change threshold (misses knees, splits noise on real devices), a ratio-of-slopes rule (ignores small steps, unstable when the earlier slope is near zero), a statistical test (harder to explain for the same result on this data).

### D2. Digest: battery metrics out of the defaults
`battery_voltage` and `battery_level` leave the default `DIGEST_METRICS` (code and Terraform). An operator can still add them. Why: the week-over-week average of a slowly declining battery is noise for the reader, and a model change or replacement makes it misleading; the maintenance figures in the same digest (open, planned, most likely) already cover batteries.

### D3. Work orders: an instruction, not a rule report
The work-order instructions ask for a short instruction to a technician: the action first, the device and its site, the facts behind it in plain words (e.g. "the battery is at 2.64 V and will reach its cut-off in about 4 days"), and the urgency. They forbid rule names, thresholds, probabilities and reason numbering. The grounding check (numbers present in the input) is unchanged.

## Risks / Trade-offs

- [A real device's voltage varies with temperature by more than 0.01 V over a day] → a daily cycle is not a lasting departure: the split needs both sides to be straight lines for 12 hours. Real deployments would revisit `departure` with their data; it is a constant per kind.
- [A change point is only seen 12 hours after it happens] → acceptable for a forecast measured in days; a replacement (a large step) is still taken into account immediately.
