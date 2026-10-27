import { type Page, type Route, test as base, expect } from '@playwright/test'
import { FAKE_ENV } from './env.mjs'

/**
 * Fakes Microsoft Entra ID (authorization code + PKCE redirect flow) and the platform API at their real URLs, so the
 * production build runs unchanged in the browser, under its real Content Security Policy.
 */
const LOGIN = 'https://login.microsoftonline.com'
const TENANT = FAKE_ENV.ENTRA_TENANT_ID
const API = FAKE_ENV.API_BASE_URL

export interface FakeUser {
  oid: string
  name: string
  upn: string
  roles: ('DeviceData.Read' | 'DeviceData.Write')[]
}

export const EDITOR: FakeUser = { oid: 'e2e-editor', name: 'Erin Editor', upn: 'erin@contoso.example', roles: ['DeviceData.Write'] }
export const READER: FakeUser = { oid: 'e2e-reader', name: 'Rob Reader', upn: 'rob@contoso.example', roles: ['DeviceData.Read'] }
export const NO_ROLE: FakeUser = { oid: 'e2e-none', name: 'Nina Nobody', upn: 'nina@contoso.example', roles: [] }

interface Device {
  deviceId: string
  siteId: string
  displayName: string | null
  description: string | null
  location: { latitude: number; longitude: number; label: string | null } | null
  lastPayloadFormat: string
  firstSeenAt: string
  lastSeenAt: string
  updatedAt: string | null
  updatedBy: { objectId: string; displayName: string } | null
  version: number
}

const b64url = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')

function seedDevices(): Device[] {
  const now = Date.now()
  const formats = ['ruuvi-df5', 'bthome-v2', 'cayenne-lpp']
  return ['paris-01', 'lyon-01', 'marseille-01'].flatMap((siteId, s) =>
    Array.from({ length: 12 }, (_, i) => ({
      deviceId: `${siteId}-${formats[i % 3].split('-')[0]}-${String(i + 1).padStart(3, '0')}`,
      siteId,
      displayName: null,
      description: null,
      location: null,
      lastPayloadFormat: formats[i % 3],
      firstSeenAt: new Date(now - 86_400_000).toISOString(),
      lastSeenAt: new Date(now - (i === 5 ? 40 : s + i) * 60_000).toISOString(),
      updatedAt: null,
      updatedBy: null,
      version: 1,
    })),
  )
}

export class FakePlatform {
  user: FakeUser = EDITOR
  devices = seedDevices()
  /** Access tokens presented to the API. */
  readonly tokens: string[] = []
  private nonce = ''

  async install(page: Page) {
    await page.route(`${LOGIN}/**`, (route) => this.login(route))
    await page.route(`${API}/**`, (route) => this.api(route))
  }

  private async login(route: Route) {
    const request = route.request()
    const url = new URL(request.url())
    const cors = { 'Access-Control-Allow-Origin': '*' }
    if (url.pathname.endsWith('/discovery/instance')) {
      return route.fulfill({
        headers: cors,
        json: {
          tenant_discovery_endpoint: `${LOGIN}/${TENANT}/v2.0/.well-known/openid-configuration`,
          'api-version': '1.1',
          metadata: [{ preferred_network: 'login.microsoftonline.com', preferred_cache: 'login.windows.net', aliases: ['login.microsoftonline.com', 'login.windows.net', 'login.microsoft.com', 'sts.windows.net'] }],
        },
      })
    }
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({
        headers: cors,
        json: {
          issuer: `${LOGIN}/${TENANT}/v2.0`,
          authorization_endpoint: `${LOGIN}/${TENANT}/oauth2/v2.0/authorize`,
          token_endpoint: `${LOGIN}/${TENANT}/oauth2/v2.0/token`,
          end_session_endpoint: `${LOGIN}/${TENANT}/oauth2/v2.0/logout`,
          jwks_uri: `${LOGIN}/${TENANT}/discovery/v2.0/keys`,
          response_modes_supported: ['query', 'fragment', 'form_post'],
        },
      })
    }
    if (url.pathname.endsWith('/oauth2/v2.0/authorize')) {
      this.nonce = url.searchParams.get('nonce') ?? ''
      const redirect = `${url.searchParams.get('redirect_uri')}#code=fake-code&state=${encodeURIComponent(url.searchParams.get('state') ?? '')}&client_info=${this.clientInfo()}`
      return route.fulfill({ status: 302, headers: { Location: redirect } })
    }
    if (url.pathname.endsWith('/oauth2/v2.0/token')) {
      const now = Math.floor(Date.now() / 1000)
      const idToken = [
        b64url({ alg: 'RS256', typ: 'JWT', kid: 'fake' }),
        b64url({ aud: FAKE_ENV.WEB_CLIENT_ID, iss: `${LOGIN}/${TENANT}/v2.0`, iat: now, nbf: now, exp: now + 3600, name: this.user.name, preferred_username: this.user.upn, oid: this.user.oid, sub: this.user.oid, tid: TENANT, nonce: this.nonce, ver: '2.0' }),
        'signature',
      ].join('.')
      return route.fulfill({
        headers: cors,
        json: {
          token_type: 'Bearer',
          scope: `${FAKE_ENV.API_SCOPE} openid profile offline_access`,
          expires_in: 3600,
          ext_expires_in: 3600,
          access_token: `access-token-for-${this.user.oid}`,
          refresh_token: 'fake-refresh-token',
          id_token: idToken,
          client_info: this.clientInfo(),
        },
      })
    }
    if (url.pathname.endsWith('/oauth2/v2.0/logout')) {
      return route.fulfill({ status: 302, headers: { Location: url.searchParams.get('post_logout_redirect_uri') ?? '/' } })
    }
    return route.fulfill({ status: 404, headers: cors, body: `Unexpected sign-in request ${url.pathname}` })
  }

  private clientInfo() {
    return b64url({ uid: this.user.oid, utid: TENANT })
  }

  private async api(route: Route) {
    const request = route.request()
    const cors = {
      'Access-Control-Allow-Origin': new URL(request.headers()['origin'] ?? 'http://localhost:4280').origin,
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, If-Match',
      'Access-Control-Allow-Methods': 'GET, PATCH',
      'Access-Control-Expose-Headers': 'ETag',
    }
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: cors })
    }
    this.tokens.push(request.headers()['authorization'] ?? '')
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const canRead = this.user.roles.length > 0
    const canWrite = this.user.roles.includes('DeviceData.Write')
    const problem = (status: number, title: string) => route.fulfill({ status, headers: { ...cors, 'Content-Type': 'application/problem+json' }, body: JSON.stringify({ title, status }) })

    if (path === '/me') {
      return route.fulfill({ headers: cors, json: { objectId: this.user.oid, displayName: this.user.name, userPrincipalName: this.user.upn, canRead, canWrite } })
    }
    if (!canRead) return problem(403, 'Forbidden')
    if (path === '/sites') {
      const sites = [...new Set(this.devices.map((d) => d.siteId))].sort()
      return route.fulfill({ headers: cors, json: sites.map((siteId) => ({ siteId, displayName: siteId, deviceCount: this.devices.filter((d) => d.siteId === siteId).length })) })
    }
    if (path === '/devices') {
      const q = url.searchParams
      const search = q.get('search')?.toLowerCase()
      const sort = q.get('sort') ?? 'displayName'
      const key = (d: Device) => ({ displayName: d.displayName ?? d.deviceId, siteId: d.siteId, firstSeen: d.firstSeenAt, lastSeen: d.lastSeenAt })[sort] ?? d.deviceId
      const items = this.devices
        .filter((d) => (!q.get('siteId') || d.siteId === q.get('siteId')) && (!search || d.deviceId.includes(search) || (d.displayName ?? '').toLowerCase().includes(search)))
        .map((d) => this.summary(d))
        .filter((d) => !q.get('status') || d.status === q.get('status'))
        .sort((a, b) => key(a as unknown as Device).localeCompare(key(b as unknown as Device)) * (q.get('order') === 'desc' ? -1 : 1))
      const page = Number(q.get('page') ?? 1)
      const pageSize = Number(q.get('pageSize') ?? 25)
      return route.fulfill({ headers: cors, json: { items: items.slice((page - 1) * pageSize, page * pageSize), page, pageSize, total: items.length } })
    }
    const match = path.match(/^\/devices\/(.+)$/)
    const device = match && this.devices.find((d) => d.deviceId === decodeURIComponent(match[1]))
    if (!device) return problem(404, 'Not Found')
    if (request.method() === 'PATCH') {
      if (!canWrite) return problem(403, 'Forbidden')
      if (request.headers()['if-match'] !== `"v${device.version}"`) return problem(412, 'Precondition Failed')
      Object.assign(device, request.postDataJSON(), {
        version: device.version + 1,
        updatedAt: new Date().toISOString(),
        updatedBy: { objectId: this.user.oid, displayName: this.user.name },
      })
    }
    return route.fulfill({ headers: { ...cors, ETag: `"v${device.version}"` }, json: { ...this.summary(device), description: device.description, location: device.location, updatedAt: device.updatedAt, updatedBy: device.updatedBy } })
  }

  private summary(d: Device) {
    const status = Date.now() - Date.parse(d.lastSeenAt) <= 15 * 60_000 ? 'reporting' : 'silent'
    return { deviceId: d.deviceId, displayName: d.displayName, siteId: d.siteId, lastPayloadFormat: d.lastPayloadFormat, firstSeenAt: d.firstSeenAt, lastSeenAt: d.lastSeenAt, status }
  }
}

/** Content Security Policy violations reported by the page. */
async function recordCspViolations(page: Page): Promise<string[]> {
  const violations: string[] = []
  await page.exposeFunction('__reportCspViolation', (message: string) => violations.push(message))
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      ;(window as unknown as { __reportCspViolation: (m: string) => void }).__reportCspViolation(`${event.violatedDirective} ${event.blockedURI}`)
    })
  })
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) violations.push(message.text())
  })
  return violations
}

export const test = base.extend<{ platform: FakePlatform; cspViolations: string[] }>({
  platform: async ({ page }, use) => {
    const platform = new FakePlatform()
    await platform.install(page)
    await use(platform)
  },
  cspViolations: async ({ page }, use) => {
    const violations = await recordCspViolations(page)
    await use(violations)
    expect(violations, 'Content Security Policy violations').toEqual([])
  },
})

export { expect }
