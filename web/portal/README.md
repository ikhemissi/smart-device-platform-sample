# Web portal

Browser front end of the Smart Device Platform:
- Microsoft Entra ID sign-in;
- the device list (site and status filters, search, sorting, paging) and, for Editors, editing device information;
- the device page, with a chart of up to four metrics over 1 h, 24 h, 7 d, 30 d or a custom range, and the latest 20 measurements, refreshed every 30 s;
- when anomaly detection is deployed (M7), the device's anomalies over the selected period on the device page, and an **Anomalies** page listing the recent anomalies of the analysed sites (site and status filters, links to the device page over each anomaly's period), where Editors review the automated classification (verdict and optional comment);
- when site digests are deployed (M8), a **Digest** card on the site page, above the certificates: the latest digest's brief (device ids of the figures link to their pages), period and generation time, and its figures (devices and silent ones, metrics with the change from the previous 7 days, alerts, anomalies, rejected messages, certificates expiring within 30 days). Editors select **Regenerate**: the card says a new digest is being generated and refreshes every 20 s until the digest job serves the request.

It implements the `web-portal` and `telemetry-visualization` specs and calls the platform API through API Management (ADR-0007).

Stack: React 19, TypeScript, Vite, Fluent UI React v9, MSAL React (authorization code flow with PKCE, redirect), TanStack Query, React Router, Apache ECharts (tree-shaken). It is hosted on Azure Static Web Apps (Free) without SWA authentication or linked backends.

## How it works

- **Runtime configuration:** `/config.json` (`tenantId`, `clientId`, `apiScope`, `apiBaseUrl`, and the optional features `assistantEnabled`, `anomaliesEnabled`, `anomalySites`, `digestsEnabled`) is read at startup, so the same build serves every environment. [scripts/write-config.mjs](scripts/write-config.mjs) generates it from the azd environment, through azd's `prepackage` hook or `npm run config`, together with `staticwebapp.config.json`. That file holds the SPA fallback, caching and the security headers; its CSP `connect-src` lists only this environment's API and Entra ID.
- **Permissions:** `GET /api/v1/me` decides what is shown; the portal never parses tokens.
  - Users without a role get the access-denied page, and no data request is sent.
  - Edit actions appear only when `canWrite` is true.
- **List state:** filters, search, sort and page live in the URL, so links can be shared and reloads keep the view. Search is debounced (300 ms).
- **Editing:** sends a JSON Merge Patch with `If-Match`. On 412 the dialog explains that someone else changed the device and shows the latest values. API validation errors appear on the corresponding fields.
- **Optional features:** `assistantEnabled` is on when the device analyst is deployed (`DEVICE_ANALYST_URL`; `PORTAL_ASSISTANT=false` hides it). `anomaliesEnabled` is on when the anomaly detector is deployed (`ANOMALY_DETECTOR_JOB_NAME` is not empty; `PORTAL_ANOMALIES=false` hides it), and `anomalySites` comes from `ANOMALY_SITES` (default `paris-01`): the device page of another site says that its anomalies are not analysed, without asking the API. `digestsEnabled` is on when the digest job is deployed (`DIGEST_JOB_NAME` is not empty; `PORTAL_DIGESTS=false` hides the card); the API says which sites get digests, and the card of another site says that it has no digest.
- **Session and errors:** tokens renew silently, and you're sent back to sign-in only when renewal fails. Failed requests show a non-technical message with **Try again**; network errors, 5xx and 429 are retried automatically twice first.

## Development

```bash
npm ci
npm run config      # writes public/config.json for the selected azd environment
npm run dev         # http://localhost:5173 (a registered redirect URI; APIM CORS allows this origin)
npm test            # component tests (Vitest, Testing Library, MSW), one per spec scenario
npm run lint        # oxlint
npm run build       # type check and production build into dist/
```

## End-to-end tests

`npm run e2e` runs the production build (`dist/`) in Chromium with Playwright:
- **Site server:** [e2e/site-server.mjs](e2e/site-server.mjs) serves the build the way Static Web Apps does, including fallback and headers, with the same generator as the deployed configuration.
- **Network fakes:** Entra ID (discovery, authorize redirect, token, sign-out) and the API are faked at the network level on their real URLs. The CSP therefore applies as in Azure, and any violation fails the test.
- **What runs:** a sign-in → filter → edit → reload smoke flow, the Reader and no-role views, keyboard-only operation, sites, alerting, anomalies (device card, Anomalies page and an Editor's review), site digests (figures, device links, an Editor's regeneration, missing brief, site without digests), and axe-core checks (WCAG 2.1 A/AA; no serious or critical violations). The fake environment enables anomaly detection for `paris-01`, and digests (for `paris-01` and `lyon-01` in the fake API).

Where the browsers' system libraries can't be installed, `npm run e2e:podman` runs the same tests in the official Playwright image, without network access.

`npm run e2e:env` runs [e2e-env/](e2e-env/) against the current azd environment, with network access and your Azure CLI token:
- the production build runs in Chromium, and only sign-in is faked;
- every API call is forwarded to the environment's API Management.

It measures the 30-day chart render time (history cache flushed first, then again warm) and follows a live simulated device until its next measurement appears in the table (M3 tasks 4.6 and 4.7).

Interactive Entra sign-in is outside these tests; the demo guide [docs/demo/m2-device-list.md](../../docs/demo/m2-device-list.md) covers it against a deployed environment.

## Deployment

`azd deploy web`: azd builds the app, the `prepackage` hook writes the environment files, and the SWA CLI uploads `dist/`. The Static Web App is in `eastus2`; the API it calls stays in the main region.
