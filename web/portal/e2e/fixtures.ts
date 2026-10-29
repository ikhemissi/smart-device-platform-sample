import { createHash } from 'node:crypto'
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

const RUUVI_METRICS = [
  ['temperature', '°C', 'Air temperature'],
  ['humidity', '%', 'Relative humidity'],
  ['pressure', 'hPa', 'Atmospheric pressure'],
  ['battery_voltage', 'V', 'Battery voltage'],
] as const

/** Deterministic readings: a daily temperature cycle and a slower humidity wave. */
function reading(metric: string, t: number): number {
  const hours = t / 3_600_000
  switch (metric) {
    case 'temperature':
      return Math.round((21 + 3 * Math.sin((2 * Math.PI * (hours - 9)) / 24) + 0.3 * Math.sin(hours * 7)) * 100) / 100
    case 'humidity':
      return Math.round((48 - 6 * Math.sin((2 * Math.PI * (hours - 9)) / 24)) * 10) / 10
    case 'pressure':
      return Math.round((1012 + 2 * Math.sin(hours / 30)) * 10) / 10
    default:
      return 3.01
  }
}

const LADDER = [60, 300, 900, 3600, 21_600, 86_400]

interface FakeCertificate {
  siteId: string
  fingerprint: string
  slot: 'primary' | 'secondary'
  subject: string
  issuer: string
  notBefore: string
  notAfter: string
  status: 'valid' | 'expiring' | 'expired'
  addedBy: string
  addedByName: string | null
  addedAt: string
}

/** Minimal multipart reader: content of the first part (the uploaded file). */
function firstPart(body: Buffer, contentType: string): string {
  const boundary = /boundary=([^;]+)/.exec(contentType)?.[1]
  const text = body.toString('latin1')
  if (!boundary) return text
  const part = text.split(`--${boundary}`)[1] ?? ''
  return part.slice(part.indexOf('\r\n\r\n') + 4).replace(/\r\n$/, '')
}

export class FakePlatform {
  user: FakeUser = EDITOR
  devices = seedDevices()
  /** Latest measurements per device (most recent first), extended by `report()`. */
  readonly sites = new Map<string, string>()
  readonly certificates: FakeCertificate[] = []
  readonly audit: { siteId: string; action: string; fingerprint: string | null }[] = []
  /** Thresholds created through the portal (M5); no alert is ever raised by the fake. */
  readonly thresholds: { id: string; deviceId: string; metric: string; min: number | null; max: number | null; recipients: string[]; enabled: boolean }[] = []
  private readonly latest = new Map<string, { measuredAt: string; payloadFormat: string; metrics: Record<string, number>; attributes: Record<string, string> }[]>()
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
      'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE',
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
    if (path === '/sites' && request.method() === 'POST') {
      if (!canWrite) return problem(403, 'Forbidden')
      const { siteId, displayName } = request.postDataJSON() as { siteId: string; displayName: string }
      if (this.sites.has(siteId) || this.devices.some((d) => d.siteId === siteId)) return problem(409, 'Conflict')
      this.sites.set(siteId, displayName)
      this.audit.unshift({ siteId, action: 'SITE_CREATED', fingerprint: null })
      return route.fulfill({ status: 201, headers: cors, json: { siteId, displayName, certificateCount: 0 } })
    }
    if (path === '/sites') {
      const ids = [...new Set([...this.devices.map((d) => d.siteId), ...this.sites.keys()])].sort()
      return route.fulfill({
        headers: cors,
        json: ids.map((siteId) => ({ siteId, displayName: this.sites.get(siteId) ?? siteId, deviceCount: this.devices.filter((d) => d.siteId === siteId).length })),
      })
    }
    if (path === '/site-certificates') {
      return route.fulfill({ headers: cors, json: this.certificates })
    }
    const trust = path.match(/^\/sites\/([^/]+)\/(certificates|audit)(?:\/([0-9a-f]{64}))?$/)
    if (trust) {
      const siteId = decodeURIComponent(trust[1])
      if (!this.sites.has(siteId) && !this.devices.some((d) => d.siteId === siteId)) return problem(404, 'Not Found')
      if (trust[2] === 'audit') {
        return route.fulfill({
          headers: cors,
          json: this.audit.filter((a) => a.siteId === siteId).map((a, i) => ({ id: i + 1, siteId, occurredAt: new Date().toISOString(), actorId: this.user.oid, actorName: this.user.name, action: a.action, fingerprint: a.fingerprint, details: {} })),
        })
      }
      const ofSite = () => this.certificates.filter((c) => c.siteId === siteId)
      if (request.method() === 'GET') return route.fulfill({ headers: cors, json: ofSite() })
      if (!canWrite) return problem(403, 'Forbidden')
      if (request.method() === 'POST') {
        const pem = firstPart(request.postDataBuffer() ?? Buffer.alloc(0), request.headers()['content-type'] ?? '')
        if (pem.includes('PRIVATE KEY')) return problem(400, 'Certificate rejected')
        if (ofSite().length >= 2) return problem(409, 'Conflict')
        const fingerprint = createHash('sha256').update(pem).digest('hex')
        const certificate: FakeCertificate = {
          siteId, fingerprint, slot: ofSite().length === 0 ? 'primary' : 'secondary',
          subject: `CN=${siteId} uploaded CA`, issuer: `CN=${siteId} uploaded CA`,
          notBefore: new Date(Date.now() - 86_400_000).toISOString(), notAfter: new Date(Date.now() + 365 * 86_400_000).toISOString(),
          status: 'valid', addedBy: this.user.oid, addedByName: this.user.name, addedAt: new Date().toISOString(),
        }
        this.certificates.push(certificate)
        this.audit.unshift({ siteId, action: 'CERTIFICATE_ADDED', fingerprint })
        return route.fulfill({ status: 201, headers: cors, json: { certificate, deviceOperation: null } })
      }
      const index = this.certificates.findIndex((c) => c.siteId === siteId && c.fingerprint === trust[3])
      if (index < 0) return problem(404, 'Not Found')
      const [removed] = this.certificates.splice(index, 1)
      ofSite().forEach((c) => (c.slot = 'primary'))
      this.audit.unshift({ siteId, action: 'CERTIFICATE_REMOVED', fingerprint: removed.fingerprint })
      const devices = this.devices.filter((d) => d.siteId === siteId).length
      const operation = ofSite().length === 0 ? { id: 'op-1', siteId, kind: 'DISABLE_DEVICES', status: 'DONE', deviceCount: devices } : null
      return route.fulfill({ headers: cors, json: { certificate: removed, deviceOperation: operation } })
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
    const alerting = path.match(/^\/devices\/([^/]+)\/(thresholds|alerts)$/)
    if (alerting) {
      const deviceId = decodeURIComponent(alerting[1])
      if (alerting[2] === 'alerts') return route.fulfill({ headers: cors, json: [] })
      const view = (t: (typeof this.thresholds)[number]) => ({
        ...t, unit: '°C', etag: `"${t.id}.1"`, updatedAt: new Date().toISOString(), updatedByName: this.user.name, openAlertId: null,
      })
      if (request.method() === 'POST') {
        if (!canWrite) return problem(403, 'Forbidden')
        const body = request.postDataJSON() as { metric: string; min: number | null; max: number | null; recipients: string[]; enabled?: boolean }
        const threshold = { id: crypto.randomUUID(), deviceId, ...body, enabled: body.enabled ?? true }
        this.thresholds.push(threshold)
        return route.fulfill({ status: 201, headers: cors, json: view(threshold) })
      }
      return route.fulfill({ headers: cors, json: this.thresholds.filter((t) => t.deviceId === deviceId).map(view) })
    }
    const history = path.match(/^\/devices\/([^/]+)\/(metrics|measurements\/latest|series)$/)
    if (history) {
      const deviceId = decodeURIComponent(history[1])
      if (!this.devices.some((d) => d.deviceId === deviceId)) return problem(404, 'Not Found')
      if (history[2] === 'metrics') {
        return route.fulfill({ headers: cors, json: RUUVI_METRICS.map(([metric, unit, description]) => ({ metric, unit, description, lastSeenAt: new Date().toISOString() })) })
      }
      if (history[2] === 'measurements/latest') {
        return route.fulfill({ headers: cors, json: this.latestOf(deviceId).slice(0, Number(url.searchParams.get('count') ?? 20)) })
      }
      return route.fulfill({ headers: cors, json: this.series(deviceId, url) })
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

  private latestOf(deviceId: string) {
    if (!this.latest.has(deviceId)) {
      const now = Math.floor(Date.now() / 30_000) * 30_000
      this.latest.set(deviceId, Array.from({ length: 20 }, (_, i) => this.measurement(now - (i + 1) * 30_000)))
    }
    return this.latest.get(deviceId)!
  }

  private measurement(t: number) {
    return {
      measuredAt: new Date(t).toISOString(),
      payloadFormat: 'ruuvi-df5',
      metrics: Object.fromEntries(RUUVI_METRICS.map(([m]) => [m, reading(m, t)])),
      attributes: { mac: 'CB:B8:33:4C:88:4F' },
    }
  }

  /** The device reports a new measurement now. */
  report(deviceId: string): string {
    const list = this.latestOf(deviceId)
    const next = this.measurement(Date.parse(list[0].measuredAt) + 30_000)
    list.unshift(next)
    return next.measuredAt
  }

  /** Series like the API: raw at 30 s reporting when it fits maxPoints, otherwise buckets from the fixed ladder. */
  private series(deviceId: string, url: URL) {
    const metrics = (url.searchParams.get('metrics') ?? '').split(',')
    const from = Date.parse(url.searchParams.get('from')!)
    const to = url.searchParams.has('to') ? Date.parse(url.searchParams.get('to')!) : Date.now()
    const maxPoints = Number(url.searchParams.get('maxPoints') ?? 500)
    const rawCount = Math.floor((to - from) / 30_000)
    const bucket = rawCount <= maxPoints ? 0 : LADDER.find((b) => Math.ceil((to - from) / 1000 / b) <= maxPoints) ?? 86_400
    const unit = (m: string) => RUUVI_METRICS.find(([k]) => k === m)?.[1] ?? null
    const series = metrics.map((metric) => {
      const points = []
      if (!bucket) {
        for (let t = Math.ceil(from / 30_000) * 30_000; t < to; t += 30_000) points.push({ t: new Date(t).toISOString(), value: reading(metric, t) })
      } else {
        const size = bucket * 1000
        for (let b = Math.floor(from / size) * size; b < to; b += size) {
          const values = []
          for (let t = b; t < b + size; t += Math.max(30_000, size / 20)) values.push(reading(metric, t))
          points.push({
            t: new Date(b).toISOString(),
            min: Math.min(...values),
            avg: Math.round((values.reduce((a, v) => a + v, 0) / values.length) * 100) / 100,
            max: Math.max(...values),
            count: Math.round(size / 30_000),
          })
        }
      }
      return { metric, unit: unit(metric), points }
    })
    const resolution = bucket ? { 60: '1m', 300: '5m', 900: '15m', 3600: '1h', 21600: '6h', 86400: '1d' }[bucket] : 'raw'
    return { deviceId, from: new Date(from).toISOString(), to: new Date(to).toISOString(), resolution, ...(bucket ? { bucketSeconds: bucket } : {}), series }
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

/** Regression guard: no modal left the application hidden from assistive technology (aria-hidden on the app root). */
export async function expectAppAccessible(page: Page) {
  await expect(page.locator('#root')).not.toHaveAttribute('aria-hidden', 'true')
}

export { expect }

/**
 * Waits until no CSS or Web Animations animation is running: Fluent dialogs fade in, and axe measures colour contrast
 * against the (partly transparent) intermediate frame otherwise.
 */
export async function animationsSettled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))
}
