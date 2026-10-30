# Tasks: M8 — Site digest

## 1. Data and infrastructure

- [x] 1.1 Migration V9 (`sdp.site_digests`, `sdp.site_digest_requests`, grants for `sdp_insights` without alert recipients and `sdp_catalog`) and digest retention in the maintenance task; verify with db-migrator integration tests (privileges of both roles, one pending request per site, retention)
- [x] 1.2 Terraform: digest job (same image and identity as the anomaly detector, placeholder image), settings `DIGEST_ENABLED`, `DIGEST_SITES`, `DIGEST_METRICS` with the feature switch, device-api and agent environment, build script and post-provision step updating both jobs; verify `azd provision --preview`, `azd provision`, and with the feature switched off a plan without the digest job

## 2. Digest job

- [x] 2.1 Figures (design D3): devices, metrics with comparison, alerts without recipients, anomalies, rejected messages, expiring certificates, bounded lists; verify unit tests on seeded data and a live computation for `paris-01` against `sdp-dev`
- [x] 2.2 Brief with `gpt-6-luna` (design D4); verify unit tests (prompt holds the figures only, failure leaves no brief) and live briefs for `paris-01` and `lyon-01` whose numbers all appear in their figures
- [ ] 2.3 Run orchestration (due scheduled digests, requested ones, missing briefs; design D2), persistence, OpenTelemetry (design D10) and `--dry-run --site` (design D11); verify unit tests with fakes and a local dry run against `sdp-dev`
- [ ] 2.4 Deploy the job; verify the day's digests of every configured site are generated, the run's trace in Application Insights, and that a second run generates nothing

## 3. Platform API

- [ ] 3.1 device-api: `GET /api/v1/sites/{siteId}/digest` and `POST /api/v1/sites/{siteId}/digest/requests` (design D6), OpenAPI regenerated; verify integration tests (latest digest, no digest yet, site not configured, feature off, pending request returned, 403 for Readers, 409) and the operations through API Management after provisioning; then a request served by the job within 10 minutes

## 4. Portal

- [ ] 4.1 `digestsEnabled` in `config.json`; *Digest* card on the site page with figures, linked device ids, *Regenerate* for Editors and the pending state (design D7); verify component tests and the offline Playwright suite (including accessibility)
- [ ] 4.2 Deploy the portal; verify with the user in the environment (digest of `paris-01`, regenerate it)

## 5. Assistant

- [ ] 5.1 `get_site_digest` tool (design D8); verify unit tests and, after deployment, the question "What happened at lyon-01 in the last day?" answered from the digest

## 6. CI, documentation and demo

- [ ] 6.1 CI and scans cover the new code (same project and image as the detector); verify a green run on `main`
- [ ] 6.2 README (capability, flow, settings), detector README, AGENTS.md and implementation guidelines (§10); verify links and commands
- [ ] 6.3 Demo guide `docs/demo/m8-site-digest.md`; verify it as written (with the user for the portal steps)
