import { act, screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DIGEST_PENDING_REFRESH_MS } from '../../api/queries'
import type { Digest, DigestRequest, SiteDigest } from '../../api/types'
import { NO_FEATURES } from '../../features'
import { renderPortal } from '../../test/render'
import { API, EDITOR, READER, requests } from '../../test/server'
import { briefParts, figureDeviceIds } from './digestText'

const DIGESTS = { ...NO_FEATURES, digests: true }
const SITE = 'paris-01'
const SILENT = 'paris-01-ruuvi3-001'
const ALERTED = 'paris-01-ruuvi3-002'

const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString()

const digest = (id: number, changes: Partial<Digest> = {}): Digest => ({
  id,
  trigger: 'scheduled',
  periodFrom: hoursAgo(25),
  periodTo: hoursAgo(1),
  comparisonFrom: hoursAgo(25 + 7 * 24),
  generatedAt: hoursAgo(1),
  brief: `Over the last day, 9 of 10 devices reported; ${SILENT} went silent. One temperature alert on ${ALERTED} closed.`,
  briefModel: 'gpt-6-luna',
  requestedBy: null,
  figures: {
    site: { id: SITE, name: SITE },
    devices: { total: 10, reporting: 9, silent: { total: 1, items: [{ deviceId: SILENT, name: null, lastSeenAt: hoursAgo(13) }] } },
    metrics: [
      { metric: 'temperature', unit: '°C', devices: 10, average: 20.7, min: 12.99, max: 35.37, comparisonAverage: 20.5, change: 0.2, changePercent: 1 },
      { metric: 'humidity', unit: '%', devices: 10, average: 45.12, min: 30, max: 60 },
    ],
    alerts: {
      openNow: 0,
      total: 1,
      items: [{ deviceId: ALERTED, metric: 'temperature', direction: 'above', status: 'closed', openedAt: hoursAgo(5), closedAt: hoursAgo(4), worstValue: 31.2 }],
    },
    anomalies: {
      byStatus: { confirmed: 11, reviewed: 2, to_review: 1 },
      byClassification: { real_event: 6, sensor_fault: 8 },
      total: 14,
      items: [{ deviceId: SILENT, metric: 'temperature', kind: 'deviation', status: 'confirmed', classification: 'real_event', confidencePercent: 85, startedAt: hoursAgo(6), endedAt: hoursAgo(5) }],
    },
    rejectedMessages: { total: 3, byReason: { unknown_format: 3 } },
    certificates: { expiringWithin30Days: [{ slot: 'primary', subject: 'CN=paris-01 CA', notAfter: hoursAgo(-12 * 24), daysLeft: 12 }] },
  },
  ...changes,
})

/** The site page's other cards, and the digest endpoint answering `state()`. */
function handlers(state: () => SiteDigest, onRequest?: (request: Request) => Response | DigestRequest) {
  return [
    http.get(`${API}/sites/:siteId/certificates`, () => HttpResponse.json([])),
    http.get(`${API}/sites/:siteId/audit`, () => HttpResponse.json([])),
    http.get(`${API}/sites/:siteId/digest`, ({ request }) => {
      requests.push(request)
      return HttpResponse.json(state())
    }),
    http.post(`${API}/sites/:siteId/digest/requests`, ({ request }) => {
      requests.push(request)
      const result = onRequest?.(request) ?? { requestedAt: new Date().toISOString(), requestedBy: EDITOR.displayName }
      return result instanceof Response ? result : HttpResponse.json(result, { status: 202 })
    }),
  ]
}

const digestRequests = () => requests.filter((r) => r.method === 'GET' && new URL(r.url).pathname.endsWith('/digest'))

describe('site digest card', () => {
  afterEach(() => vi.useRealTimers())

  it('shows Readers the brief with linked device ids and the figures, without Regenerate', async () => {
    const latest = digest(1)
    renderPortal(`/sites/${SITE}`, READER, { features: DIGESTS, handlers: handlers(() => ({ siteId: SITE, enabled: true, digest: latest, pendingRequest: null })) })

    const card = (await screen.findByRole('heading', { name: 'Digest', level: 2 })).closest('.fui-Card') as HTMLElement
    const brief = await within(card).findByText(/Over the last day/)
    expect(brief).toHaveTextContent(`Over the last day, 9 of 10 devices reported; ${SILENT} went silent.`)
    expect(within(brief).getByRole('link', { name: SILENT })).toHaveAttribute('href', `/devices/${SILENT}`)
    expect(within(brief).getByRole('link', { name: ALERTED })).toHaveAttribute('href', `/devices/${ALERTED}`)

    const metrics = within(card).getByRole('table', { name: 'Metrics' })
    const headers = within(metrics).getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual(['Metric', 'Unit', 'Average', 'Previous 7 days', 'Change', 'Change %'])
    const [temperature, humidity] = within(metrics).getAllByRole('row').slice(1)
    expect(within(temperature).getAllByRole('cell').map((c) => c.textContent)).toEqual(['temperature', '°C', '20.7', '20.5', '+0.2', '+1 %'])
    expect(within(humidity).getAllByRole('cell').map((c) => c.textContent)).toEqual(['humidity', '%', '45.12', '–', '–', '–'])

    const devices = within(card).getByRole('region', { name: 'Devices' })
    expect(devices).toHaveTextContent('9 of 10 reporting, 1 silent:')
    expect(within(devices).getByRole('link', { name: SILENT })).toHaveAttribute('href', `/devices/${SILENT}`)
    expect(within(devices).getByText(/last seen/)).toHaveTextContent('last seen 13 hours ago')
    const alerts = within(card).getByRole('region', { name: 'Alerts' })
    expect(alerts).toHaveTextContent('0 open now, 1 in the period:')
    expect(alerts).toHaveTextContent('temperature above threshold, worst 31.2 °C, closed')
    const anomalies = within(card).getByRole('region', { name: 'Anomalies' })
    expect(anomalies).toHaveTextContent('By status: Confirmed: 11, Reviewed: 2, To review: 1')
    expect(anomalies).toHaveTextContent('By classification: Real event: 6, Sensor fault: 8')
    expect(anomalies).toHaveTextContent('temperature, deviation, Real event 85 %, confirmed')
    expect(within(anomalies).getByText('and 13 more')).toBeInTheDocument()
    expect(within(card).getByRole('region', { name: 'Rejected messages' })).toHaveTextContent('3: unknown_format: 3')
    expect(within(card).getByRole('region', { name: 'Certificates expiring within 30 days' })).toHaveTextContent('CN=paris-01 CA (primary): 12 day(s) left')
    expect(card.querySelector(`time[datetime="${latest.periodTo}"]`)).not.toBeNull()
    expect(within(card).getByText(/^Period/)).toHaveTextContent('generated 1 hour ago')

    expect(within(card).queryByRole('button', { name: 'Regenerate' })).not.toBeInTheDocument()
    // Above the certificates card.
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings.indexOf('Digest')).toBeLessThan(headings.indexOf('Trusted certificates'))
  })

  it('lets an Editor regenerate the digest and shows the new one once generated', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let state: SiteDigest = { siteId: SITE, enabled: true, digest: digest(1), pendingRequest: null }
    const pending: DigestRequest = { requestedAt: new Date().toISOString(), requestedBy: EDITOR.displayName }
    const { user } = renderPortal(`/sites/${SITE}`, EDITOR, {
      features: DIGESTS,
      advanceTimers: vi.advanceTimersByTime,
      handlers: handlers(
        () => state,
        () => {
          state = { ...state, pendingRequest: pending }
          return pending
        },
      ),
    })

    await screen.findByText(/Over the last day/)
    await user.click(screen.getByRole('button', { name: 'Regenerate' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent(`A new digest is being generated (requested by ${EDITOR.displayName} at`)
    expect(status.querySelector(`time[datetime="${pending.requestedAt}"]`)).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Regenerate' })).toBeDisabled()
    expect(requests.filter((r) => r.method === 'POST').map((r) => new URL(r.url).pathname)).toEqual([`/api/v1/sites/${SITE}/digest/requests`])

    // Still pending at the next refresh.
    const before = digestRequests().length
    await act(() => vi.advanceTimersByTimeAsync(DIGEST_PENDING_REFRESH_MS))
    await waitFor(() => expect(digestRequests().length).toBeGreaterThan(before))
    expect(screen.getByRole('status')).toBeInTheDocument()

    state = {
      ...state,
      pendingRequest: null,
      digest: digest(2, { trigger: 'requested', requestedBy: EDITOR.displayName, generatedAt: new Date().toISOString(), brief: 'A calm day at paris-01.' }),
    }
    await act(() => vi.advanceTimersByTimeAsync(DIGEST_PENDING_REFRESH_MS))

    expect(await screen.findByText('A calm day at paris-01.')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByText(/on request of Ed Editor/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Regenerate' })).toBeEnabled()

    // No more refreshes once the request is served.
    const served = digestRequests().length
    await act(() => vi.advanceTimersByTimeAsync(3 * DIGEST_PENDING_REFRESH_MS))
    expect(digestRequests().length).toBe(served)
  })

  it('shows why a regeneration was refused', async () => {
    const { user } = renderPortal(`/sites/${SITE}`, EDITOR, {
      features: DIGESTS,
      handlers: handlers(
        () => ({ siteId: SITE, enabled: true, digest: null, pendingRequest: null }),
        () => HttpResponse.json({ title: 'Conflict', status: 409, detail: 'Site paris-01 gets no digests.' }, { status: 409 }),
      ),
    })

    expect(await screen.findByText(/No digest yet/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Regenerate' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Site paris-01 gets no digests.')
  })

  it('shows the figures and says that the brief is unavailable when the model failed', async () => {
    renderPortal(`/sites/${SITE}`, READER, {
      features: DIGESTS,
      handlers: handlers(() => ({ siteId: SITE, enabled: true, digest: digest(1, { brief: null, briefModel: null }), pendingRequest: null })),
    })

    expect(await screen.findByText('Brief unavailable')).toBeInTheDocument()
    expect(screen.getByText(/a later run adds it/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Metrics' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Devices' })).toHaveTextContent('9 of 10 reporting')
  })

  it('says that a site without digests has none, without Regenerate', async () => {
    renderPortal(`/sites/${SITE}`, EDITOR, {
      features: DIGESTS,
      handlers: handlers(() => ({ siteId: SITE, enabled: false, digest: null, pendingRequest: null })),
    })

    expect(await screen.findByText(/This site has no digest/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Regenerate' })).not.toBeInTheDocument()
  })

  it('is not shown when digests are switched off, without asking the API', async () => {
    renderPortal(`/sites/${SITE}`, EDITOR, { handlers: handlers(() => ({ siteId: SITE, enabled: true, digest: digest(1), pendingRequest: null })) })

    expect(await screen.findByRole('heading', { name: 'Trusted certificates' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Digest' })).not.toBeInTheDocument()
    expect(digestRequests()).toHaveLength(0)
  })
})

describe('digest brief', () => {
  it('links whole device ids from the figures only', () => {
    const ids = figureDeviceIds(digest(1).figures)
    expect(ids).toEqual([ALERTED, SILENT].sort((a, b) => b.length - a.length || a.localeCompare(b)))
    expect(briefParts(`${SILENT}, ${SILENT}0 and paris-01-ruuvi3-003.`, ids)).toEqual([
      { deviceId: SILENT },
      { text: `, ${SILENT}0 and paris-01-ruuvi3-003.` },
    ])
    expect(briefParts('Nothing happened.', [])).toEqual([{ text: 'Nothing happened.' }])
  })
})
