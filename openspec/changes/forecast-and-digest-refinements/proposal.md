# Proposal

## Why

The first days of M9 in `sdp-dev` showed three rough edges ([PRD-009](../../../docs/prd/PRD-009-m9-maintenance.md), [PRD-008](../../../docs/prd/PRD-008-m8-site-digest.md)):
- a battery that starts declining fast without a visible step (the end-of-life "knee" of a real battery, or the simulator's model change on `paris-01-ruuvi5-003`) is forecast from a mix of its old slow trend and the new fast one, so it reaches the queue days late;
- the site digest compares battery averages week over week, which says nothing useful (batteries are now covered by the maintenance queue in the same digest) and, after the simulator change, produced a misleading "battery level fell 17.7 %";
- work orders read like rule output ("Reason 1: … below the 7.0 days threshold") rather than an instruction to a technician.

## What Changes

- **Battery forecast**: besides a sudden change of the level, a lasting departure from the earlier trend also starts a new segment: a marked steepening of the decline, or a step too small to be a replacement (the simulator change of 30 October left steps of 0.01–0.06 V that currently skew the forecast of healthy devices, e.g. 42 days left instead of about 240).
- **Site digest**: battery metrics are no longer in the default compared metrics (`DIGEST_METRICS`); batteries appear through the maintenance queue figures.
- **Work orders**: instructions ask for a plain instruction to a technician, without rule names, thresholds or reason numbering.

## Capabilities

### New Capabilities

### Modified Capabilities

- `predictive-maintenance`: the battery forecast also restarts at a lasting departure from the earlier trend (steepening or small step).
- `site-digest`: battery metrics are not compared week over week by default.

## Impact

- `agents/anomaly-detector`: `maintenance/battery.py` (change point), `maintenance/models.py` (work order instructions), `digest/config.py` (default metrics); tests.
- Terraform default of `DIGEST_METRICS`; README and detector README settings; job image rebuilt.
