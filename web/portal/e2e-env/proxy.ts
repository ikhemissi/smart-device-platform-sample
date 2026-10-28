import type { Page } from '@playwright/test'
import { FAKE_ENV } from '../e2e/env.mjs'

export const API = process.env.SDP_API_BASE_URL ?? ''
export const TOKEN = process.env.SDP_API_TOKEN ?? ''
export const DEVICE = process.env.SDP_DEVICE ?? 'paris-01-ruuvi5-001'

/**
 * Forwards the portal's API calls to the environment's API Management with a real access token: only sign-in is faked
 * (interactive Entra sign-in with MFA cannot be automated), the data path is the deployed one.
 */
export async function proxyToEnvironment(page: Page) {
  await page.route(`${FAKE_ENV.API_BASE_URL}/**`, async (route) => {
    if (route.request().method() === 'OPTIONS') {
      return route.fulfill({
        status: 204,
        headers: { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE' },
      })
    }
    const target = route.request().url().replace(FAKE_ENV.API_BASE_URL, API)
    // Server-to-server hop: no browser origin (the gateway only accepts the portal's origins).
    const { origin: _origin, referer: _referer, ...headers } = route.request().headers()
    const response = await route.fetch({ url: target, headers: { ...headers, authorization: `Bearer ${TOKEN}` } })
    await route.fulfill({ response, headers: { ...response.headers(), 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag, Location' } })
  })
}
