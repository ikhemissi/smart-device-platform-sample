import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import type { CurrentUser, DeviceDetails, DeviceSummary, Site } from '../api/types'

export const API = 'https://api.test/api/v1'

/** Requests seen by the fake API since the last render (a new log per render: late requests of a previous test are ignored). */
export let requests: Request[] = []

export function resetRequests(): Request[] {
  requests = []
  return requests
}

export const READER: CurrentUser = {
  objectId: 'oid-reader',
  displayName: 'Rita Reader',
  userPrincipalName: 'rita@contoso.example',
  canRead: true,
  canWrite: false,
}
export const EDITOR: CurrentUser = { ...READER, objectId: 'oid-editor', displayName: 'Ed Editor', canWrite: true }
export const NO_ROLE: CurrentUser = { ...READER, displayName: 'Nora None', canRead: false }

export const SITES: Site[] = [
  { siteId: 'lyon-01', displayName: 'lyon-01', deviceCount: 2 },
  { siteId: 'paris-01', displayName: 'paris-01', deviceCount: 1 },
]

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()

export const DEVICES: DeviceSummary[] = [
  { deviceId: 'lyon-01-ruuvi5-001', displayName: 'Cold room', siteId: 'lyon-01', lastPayloadFormat: 'ruuvi-df5', firstSeenAt: minutesAgo(600), lastSeenAt: minutesAgo(1), status: 'reporting' },
  { deviceId: 'lyon-01-bthome-001', displayName: null, siteId: 'lyon-01', lastPayloadFormat: 'bthome-v2', firstSeenAt: minutesAgo(600), lastSeenAt: minutesAgo(20), status: 'silent' },
  { deviceId: 'paris-01-cayenne-001', displayName: null, siteId: 'paris-01', lastPayloadFormat: 'cayenne-lpp', firstSeenAt: minutesAgo(500), lastSeenAt: minutesAgo(2), status: 'reporting' },
]

export function details(summary: DeviceSummary, changes: Partial<DeviceDetails> = {}): DeviceDetails {
  return { ...summary, description: null, location: null, updatedAt: null, updatedBy: null, ...changes }
}

/** Default fake API: filters like the real one so URL-driven views can be asserted. */
export function apiHandlers(me: CurrentUser, log: Request[] = requests, token?: string) {
  const record = (request: Request) => {
    if (!token || request.headers.get('Authorization') === 'Bearer ' + token) {
      log.push(request)
    }
  }
  return [
    http.get(`${API}/me`, ({ request }) => {
      record(request)
      return HttpResponse.json(me)
    }),
    http.get(`${API}/sites`, ({ request }) => {
      record(request)
      return HttpResponse.json(SITES)
    }),
    http.get(`${API}/devices`, ({ request }) => {
      record(request)
      const params = new URL(request.url).searchParams
      const search = params.get('search')?.toLowerCase()
      const items = DEVICES.filter(
        (d) =>
          (!params.get('siteId') || d.siteId === params.get('siteId')) &&
          (!params.get('status') || d.status === params.get('status')) &&
          (!search || d.deviceId.toLowerCase().includes(search) || (d.displayName ?? '').toLowerCase().includes(search)),
      )
      return HttpResponse.json({ items, page: Number(params.get('page') ?? 1), pageSize: 25, total: items.length })
    }),
    http.get(`${API}/devices/:id`, ({ request, params }) => {
      record(request)
      const device = DEVICES.find((d) => d.deviceId === params.id)
      return device
        ? HttpResponse.json(details(device), { headers: { ETag: '"v1"' } })
        : HttpResponse.json({ title: 'Not Found', status: 404 }, { status: 404 })
    }),
  ]
}

export const server = setupServer()
