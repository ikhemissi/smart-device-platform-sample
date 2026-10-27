# Web portal

Browser front end of the Smart Device Platform: Microsoft Entra ID sign-in, the device list (site and status filters, search, sorting, paging) and, for Editors, editing device information. It implements the `web-portal` spec of OpenSpec change `m2-device-catalog-portal` and calls the platform API through API Management (ADR-0007).

Stack: React 19, TypeScript, Vite, Fluent UI React v9, MSAL React (authorization code flow with PKCE, redirect), TanStack Query, React Router. It is hosted on Azure Static Web Apps (Free) without SWA authentication or linked backends.

## How it works

- **Runtime configuration:** `/config.json` (`tenantId`, `clientId`, `apiScope`, `apiBaseUrl`) is read at startup, so the same build serves every environment. [scripts/write-config.mjs](scripts/write-config.mjs) generates it from the azd environment, through azd's `prepackage` hook or `npm run config`, together with `staticwebapp.config.json`. That file holds the SPA fallback, caching and the security headers; its CSP `connect-src` lists only this environment's API and Entra ID.
- **Permissions:** `GET /api/v1/me` decides what is shown; the portal never parses tokens.
  - Users without a role get the access-denied page, and no data request is sent.
  - Edit actions appear only when `canWrite` is true.
- **List state:** filters, search, sort and page live in the URL, so links can be shared and reloads keep the view. Search is debounced (300 ms).
- **Editing:** sends a JSON Merge Patch with `If-Match`. On 412 the dialog explains that someone else changed the device and shows the latest values. API validation errors appear on the corresponding fields.
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
- **What runs:** a sign-in → filter → edit → reload smoke flow, the Reader and no-role views, keyboard-only operation, and axe-core checks (WCAG 2.1 A/AA; no serious or critical violations).

Where the browsers' system libraries can't be installed, `npm run e2e:podman` runs the same tests in the official Playwright image, without network access.

Interactive Entra sign-in is outside these tests; the demo guide [docs/demo/m2-device-list.md](../../docs/demo/m2-device-list.md) covers it against a deployed environment.

## Deployment

`azd deploy web`: azd builds the app, the `prepackage` hook writes the environment files, and the SWA CLI uploads `dist/`. The Static Web App is in `eastus2`; the API it calls stays in the main region.
