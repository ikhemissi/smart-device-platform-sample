#!/usr/bin/env node
// Writes the environment-specific files of the portal from the azd environment (azd `prepackage` hook, or
// `npm run config` for local development):
//   - config.json: sign-in and API settings read at startup (the same build serves every environment);
//   - staticwebapp.config.json: SPA fallback and security headers, with the CSP limited to this environment's API.
// Values come from the process environment (set by azd for hooks), otherwise from `azd env get-values`.
import { execFileSync } from 'node:child_process'
import { existsSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const REQUIRED = ['ENTRA_TENANT_ID', 'WEB_CLIENT_ID', 'API_SCOPE', 'API_BASE_URL']
export const LOGIN_ORIGIN = 'https://login.microsoftonline.com'

/** Sites analysed by the anomaly detector: ANOMALY_SITES (comma-separated), paris-01 when empty like in Terraform. */
function anomalySites(value) {
  const sites = (value ?? '').split(',').map((site) => site.trim()).filter(Boolean)
  return sites.length > 0 ? sites : ['paris-01']
}

/** The generated files for an environment; also used by the end-to-end tests' site server. */
export function portalFiles(env) {
  const config = {
    tenantId: env.ENTRA_TENANT_ID,
    clientId: env.WEB_CLIENT_ID,
    apiScope: env.API_SCOPE,
    apiBaseUrl: env.API_BASE_URL,
    // The assistant entry point appears once the device analyst is deployed (PORTAL_ASSISTANT=false hides it).
    assistantEnabled: env.PORTAL_ASSISTANT ? env.PORTAL_ASSISTANT === 'true' : Boolean(env.DEVICE_ANALYST_URL),
    // Anomalies appear once the detector is deployed (its job name is empty when ANOMALY_DETECTION_ENABLED=false;
    // PORTAL_ANOMALIES=false hides them), for the sites it analyses.
    anomaliesEnabled: env.PORTAL_ANOMALIES ? env.PORTAL_ANOMALIES === 'true' : Boolean(env.ANOMALY_DETECTOR_JOB_NAME),
    anomalySites: anomalySites(env.ANOMALY_SITES),
    // Site digests appear once the digest job is deployed (its name is empty when DIGEST_ENABLED=false;
    // PORTAL_DIGESTS=false hides them).
    digestsEnabled: env.PORTAL_DIGESTS ? env.PORTAL_DIGESTS === 'true' : Boolean(env.DIGEST_JOB_NAME),
    // The maintenance queue appears once the maintenance job is deployed (its name is empty when
    // MAINTENANCE_ENABLED=false; PORTAL_MAINTENANCE=false hides it).
    maintenanceEnabled: env.PORTAL_MAINTENANCE ? env.PORTAL_MAINTENANCE === 'true' : Boolean(env.UPKEEP_JOB_NAME),
  }
  const csp = [
    "default-src 'self'",
    "script-src 'self'",
    // Fluent UI (Griffel) inserts its styles at runtime.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    `connect-src 'self' ${new URL(env.API_BASE_URL).origin} ${LOGIN_ORIGIN}`,
    // MSAL renews tokens silently in a hidden iframe.
    `frame-src ${LOGIN_ORIGIN}`,
    "frame-ancestors 'none'",
    "form-action 'self'",
    "base-uri 'self'",
    "object-src 'none'",
  ].join('; ')
  const staticWebApp = {
    navigationFallback: {
      rewrite: '/index.html',
      exclude: ['/assets/*', '/config.json', '/*.svg', '/*.ico'],
    },
    routes: [
      { route: '/config.json', headers: { 'Cache-Control': 'no-store' } },
      { route: '/index.html', headers: { 'Cache-Control': 'no-cache' } },
      { route: '/assets/*', headers: { 'Cache-Control': 'public, max-age=31536000, immutable' } },
    ],
    globalHeaders: {
      'Content-Security-Policy': csp,
      'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
      'Cross-Origin-Opener-Policy': 'same-origin-allow-popups',
    },
    mimeTypes: { '.json': 'application/json' },
  }
  return { config, staticWebApp }
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..')
  let env = process.env
  if (REQUIRED.some((name) => !env[name])) {
    env = { ...JSON.parse(execFileSync('azd', ['env', 'get-values', '-o', 'json'], { encoding: 'utf8' })), ...process.env }
  }
  const missing = REQUIRED.filter((name) => !env[name])
  if (missing.length > 0) {
    console.error(`Missing azd environment values: ${missing.join(', ')} (run azd provision first)`)
    process.exit(1)
  }
  const { config, staticWebApp } = portalFiles(env)
  // public/ is copied into the build; dist/ is updated too when the build already ran (azd builds before packaging).
  for (const dir of ['public', 'dist']) {
    if (dir === 'public' || existsSync(join(root, dir))) {
      writeFileSync(join(root, dir, 'config.json'), JSON.stringify(config, null, 2) + '\n')
      writeFileSync(join(root, dir, 'staticwebapp.config.json'), JSON.stringify(staticWebApp, null, 2) + '\n')
      console.log(`Wrote ${dir}/config.json and ${dir}/staticwebapp.config.json (API ${config.apiBaseUrl})`)
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
