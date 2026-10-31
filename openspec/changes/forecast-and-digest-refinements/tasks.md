# Tasks

## 1. Battery forecast change point

- [x] 1.1 Add the change-point search to `maintenance/battery.py` (D1) with unit tests: steepening, small step over two hours, steady decline not split, whole-percent level staircase not split, noise not split, the last of two changes wins
- [x] 1.2 Dry run `maintenance --dry-run --no-models` on the three sites: healthy devices about -0.0015 V/day, paris-01-ruuvi5-003 about -0.035 V/day, lyon and marseille aging devices unchanged

## 2. Site digest

- [x] 2.1 Remove the battery metrics from the default digest metrics (code and Terraform) with a test; dry run `site-digest --dry-run --site lyon-01` without battery averages and with the maintenance figures

## 3. Work orders

- [x] 3.1 Rewrite the work-order instructions (D3); test; dry run with models on marseille-01: a plain instruction without rule names or thresholds, no unsupported numbers

## 4. Delivery

- [x] 4.1 Update the README, the detector README and the M8/M9 demo guides where they describe the forecast or the digest metrics
- [ ] 4.2 Tests and linters (`uv run pytest`, `uv run ruff check src tests`, `terraform fmt`/`validate`, `openspec validate --all --strict`); rebuild the job image; `azd provision --preview` then `azd provision` for the Terraform default
- [ ] 4.3 After the next hourly run in `sdp-dev`: the assessments of the healthy devices and paris-01-ruuvi5-003 match the dry run
