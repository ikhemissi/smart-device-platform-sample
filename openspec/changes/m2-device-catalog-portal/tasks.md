# Tasks: M2 — Device catalog in the web portal

## 1. Identity and access foundation

- [ ] 1.1 Add Entra app registrations (API with roles and scope, SPA), service principals and deployer role assignments in `infra/modules/identity`; verify with `az ad app show` that roles/scope exist and the deployer holds both roles
- [ ] 1.2 Add shared security auto-configuration in `libs/platform-common` (resource server, role mapping with Write ⇒ Read, problem-details handlers); verify unit tests for 401/403 and role mapping

## 2. Device API

- [ ] 2.1 Scaffold `services/device-api` (Spring Boot 4, virtual threads, passwordless datasource, actuator, springdoc); verify the service starts locally against the dev database with the developer's Entra identity
- [ ] 2.2 Add Flyway migration with device-api principal and grants; verify with Testcontainers that the role can update only the editable columns
- [ ] 2.3 Implement `GET /api/v1/me` (accessible without role) and the `x-sdp-required-role` operation annotation; verify controller tests for Reader, Editor and no-role tokens
- [ ] 2.4 Implement `GET /api/v1/devices` (filter, search, sort, paging, status, total); verify spec scenarios with Testcontainers and a 1,000-device dataset under 500 ms
- [ ] 2.5 Implement `GET /api/v1/devices/{id}` with entity tag and 404; verify spec scenarios
- [ ] 2.6 Implement `PATCH /api/v1/devices/{id}` (merge patch allow-list, validation, `If-Match`, audit fields); verify success, 412 and non-editable-field scenarios
- [ ] 2.7 Implement `GET /api/v1/sites`; verify the demo-sites scenario
- [ ] 2.8 Export `openapi.json` at build time and document the API in `services/device-api/README.md`; verify the exported file is up to date in CI (`mvn verify` fails on drift)

## 3. API gateway

- [ ] 3.1 Add `infra/modules/api` with APIM BasicV2, managed-identity Application Insights logger and the device-api container app; verify `azd provision` and that APIM traces appear in Application Insights
- [ ] 3.2 Import `openapi.json` as API `sdp-api` (`/api/v1`) with token validation, role-per-method, CORS, rate limit and trace propagation policies; verify 401/403/200 cases with `curl` using tokens from `az account get-access-token` (Azure CLI pre-authorized in dev) for a Reader, an Editor and a role-less test user
- [ ] 3.3 Verify a direct call to the device-api FQDN without token returns 401 (gateway-bypass scenario)

## 4. Web portal

- [ ] 4.1 Scaffold `web/portal` (Vite, React, TypeScript, Fluent UI v9, MSAL, TanStack Query, Router, ESLint, Vitest); verify `npm run build` and `npm test` pass
- [ ] 4.2 Implement runtime config loading and the azd `prepackage` hook writing `config.json`; verify the deployed site loads the environment's configuration
- [ ] 4.3 Implement the shell (sign-in redirect, header with user and sign-out, navigation) and the access-denied page driven by `GET /me`; verify component tests and a manual check with a user without role
- [ ] 4.4 Implement the device list (columns, status badge, site filter, debounced search, sort, paging, URL state, empty state); verify component tests for each spec scenario
- [ ] 4.5 Implement the Editor-only edit dialog with validation, `If-Match` and conflict handling; verify component tests for reader/no-action and 412 scenarios
- [ ] 4.6 Add `staticwebapp.config.json` (fallback, security headers, CSP) and verify the deployed site passes a CSP check with no console violations
- [ ] 4.7 Check keyboard-only operation and accessible names with axe (Playwright + axe-core); verify zero serious violations

## 5. Deployment and integration

- [ ] 5.1 Add `infra/modules/web` (Static Web App in `var.web_location`) and the `web` and `device-api` services to `azure.yaml`; verify `azd up` deploys the portal reachable at its default hostname
- [ ] 5.2 Playwright smoke test against the environment: sign in as Editor, filter by site, edit a device name, verify it persists; verify the test passes
- [ ] 5.3 Write the M2 demo guide `docs/demo/m2-device-list.md`; verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
