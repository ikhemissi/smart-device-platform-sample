import { screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { Anomaly, AnomalyStatus } from '../../api/types'
import { NO_FEATURES } from '../../features'
import { renderPortal } from '../../test/render'
import { API, EDITOR, READER, requests } from '../../test/server'

// ECharts needs a canvas (the chart is tested in DevicePage.test.tsx and chartOption.test.ts).
vi.mock('../../components/TelemetryChart', () => ({ TelemetryChart: () => <div role="img" aria-label="Chart" /> }))

const ANOMALIES = { features: { ...NO_FEATURES, anomalies: true, anomalySites: ['paris-01'] } }
const DEVICE = 'paris-01-cayenne-001'

const anomaly = (id: number, status: AnomalyStatus, changes: Partial<Anomaly> = {}): Anomaly => ({
  id,
  deviceId: DEVICE,
  deviceName: 'Cold room',
  siteId: 'paris-01',
  metric: 'temperature',
  unit: '°C',
  kind: 'deviation',
  startedAt: new Date(Date.now() - id * 3_600_000).toISOString(),
  endedAt: new Date(Date.now() - id * 3_600_000 + 25 * 60_000).toISOString(),
  observedMin: 28.4,
  observedMax: 31.2,
  observedMean: 30.1,
  observedLast: 30.9,
  usualValue: 21,
  spread: 1.5,
  score: 6.2,
  status,
  decision: status === 'reviewed' ? 'confirmed' : status,
  classification: status === 'pending' ? null : 'real_event',
  confidence: status === 'pending' ? null : status === 'to_review' ? 0.55 : 0.91,
  probabilities: {},
  confidenceThreshold: 0.7,
  decisionModel: 'decision-1',
  decisionModelVersion: '1',
  explanation: status === 'pending' ? null : 'The temperature rose 9 °C above its usual value for 25 minutes. The door was probably left open.',
  explanationModel: 'gpt-6-luna',
  detectedAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  review: null,
  ...changes,
})

const anomalyRequests = (path: string) => requests.filter((r) => new URL(r.url).pathname === `/api/v1${path}`).map((r) => new URL(r.url).searchParams)

describe('anomalies on the device page', () => {
  it('lists a confirmed real event with its confidence, period, values and explanation', async () => {
    const confirmed = anomaly(2, 'confirmed')
    renderPortal(`/devices/${DEVICE}`, READER, {
      ...ANOMALIES,
      handlers: [
        http.get(`${API}/devices/:id/anomalies`, ({ request }) => {
          requests.push(request)
          return HttpResponse.json([confirmed])
        }),
      ],
    })

    const table = await screen.findByRole('table', { name: 'Anomalies' })
    const row = within(table).getAllByRole('row')[1]
    expect(within(row).getByText('Confirmed')).toBeInTheDocument()
    expect(within(row).getByText('Real event · 91 %')).toBeInTheDocument()
    expect(within(row).getByText('Air temperature (°C)')).toBeInTheDocument()
    expect(row.querySelector(`time[datetime="${confirmed.startedAt}"]`)).not.toBeNull()
    expect(within(row).getByText('lasted 25 min')).toBeInTheDocument()
    expect(within(row).getByText('28.4–31.2 °C, last 30.9 °C')).toBeInTheDocument()
    expect(within(row).getByText('usual 21 °C')).toBeInTheDocument()
    expect(within(row).getByText(/The door was probably left open\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Review/ })).not.toBeInTheDocument()

    // The page's default period (24 h), with the device's default statuses.
    const [params] = anomalyRequests(`/devices/${DEVICE}/anomalies`)
    expect(params.get('status')).toBe('confirmed,to_review,reviewed')
    expect(Date.now() - Date.parse(params.get('from')!)).toBeGreaterThanOrEqual(86_400_000)
    expect(params.has('to')).toBe(false)
  })

  it('distinguishes anomalies to review from confirmed ones and shows the review', async () => {
    renderPortal(`/devices/${DEVICE}`, READER, {
      ...ANOMALIES,
      handlers: [
        http.get(`${API}/devices/:id/anomalies`, () =>
          HttpResponse.json([
            anomaly(1, 'to_review'),
            anomaly(2, 'confirmed'),
            anomaly(3, 'reviewed', { review: { reviewedAt: new Date().toISOString(), reviewedBy: 'Ed Editor', verdict: 'incorrect', comment: 'Defrost cycle' } }),
          ]),
        ),
      ],
    })

    const rows = within(await screen.findByRole('table', { name: 'Anomalies' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.dataset.status)).toEqual(['to_review', 'confirmed', 'reviewed'])
    expect(within(rows[0]).getByText('To review')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Real event · 55 %')).toBeInTheDocument()
    expect(rows[0].className).not.toBe(rows[1].className)
    expect(within(rows[2]).getByText('Classification incorrect')).toBeInTheDocument()
    expect(within(rows[2]).getByText(/by Ed Editor/)).toBeInTheDocument()
    expect(within(rows[2]).getByText('“Defrost cycle”')).toBeInTheDocument()
  })

  it('asks for dismissed anomalies on request, over a custom period', async () => {
    const from = '2026-10-20T08:00:00.000Z'
    const to = '2026-10-20T12:00:00.000Z'
    const { user } = renderPortal(`/devices/${DEVICE}?period=custom&from=${from}&to=${to}`, READER, ANOMALIES)

    expect(await screen.findByText('No anomaly in this period.')).toBeInTheDocument()
    await user.click(screen.getByRole('switch', { name: 'Show dismissed' }))

    await waitFor(() => expect(anomalyRequests(`/devices/${DEVICE}/anomalies`)).toHaveLength(2))
    const [first, second] = anomalyRequests(`/devices/${DEVICE}/anomalies`)
    expect([first.get('from'), first.get('to')]).toEqual([from, to])
    expect(second.get('status')).toBe('confirmed,to_review,reviewed,dismissed')
  })

  it('says when the device’s site is not analysed, without asking for anomalies', async () => {
    renderPortal('/devices/lyon-01-ruuvi5-001', READER, ANOMALIES)

    expect(await screen.findByText('Anomalies are not analysed for this site.')).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Show dismissed' })).not.toBeInTheDocument()
    expect(requests.some((r) => new URL(r.url).pathname.includes('anomalies'))).toBe(false)
  })

  it('has no anomalies card nor navigation entry when anomaly detection is disabled', async () => {
    renderPortal(`/devices/${DEVICE}`, READER)

    expect(await screen.findByRole('heading', { name: 'Latest measurements' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Alerts' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Anomalies' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Anomalies' })).not.toBeInTheDocument()
    expect(requests.some((r) => new URL(r.url).pathname.includes('anomalies'))).toBe(false)
  })
})

/** Fake list endpoint filtering by status and site like the API, and the review endpoint updating it. */
function anomaliesApi(items: Anomaly[], reviews: unknown[] = []) {
  return [
    http.get(`${API}/anomalies`, ({ request }) => {
      requests.push(request)
      const params = new URL(request.url).searchParams
      const statuses = (params.get('status') ?? 'confirmed,to_review').split(',')
      return HttpResponse.json(items.filter((a) => statuses.includes(a.status) && (!params.get('siteId') || a.siteId === params.get('siteId'))))
    }),
    http.post(`${API}/anomalies/:id/review`, async ({ request, params }) => {
      const body = (await request.json()) as { verdict: 'correct' | 'incorrect'; comment: string | null }
      reviews.push(body)
      const index = items.findIndex((a) => a.id === Number(params.id))
      items[index] = { ...items[index], status: 'reviewed', review: { reviewedAt: new Date().toISOString(), reviewedBy: EDITOR.displayName, ...body } }
      return HttpResponse.json(items[index])
    }),
  ]
}

describe('anomalies page', () => {
  it('lists confirmed anomalies and anomalies to review of every analysed site by default, linking to the device page', async () => {
    const items = [anomaly(1, 'to_review'), anomaly(2, 'confirmed'), anomaly(3, 'dismissed')]
    const { router, user } = renderPortal('/anomalies', READER, { ...ANOMALIES, handlers: anomaliesApi(items) })

    expect(await screen.findByRole('heading', { name: 'Anomalies', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Anomalies' })).toHaveAttribute('aria-current', 'page')
    const rows = within(await screen.findByRole('table', { name: 'Anomalies' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.dataset.status)).toEqual(['to_review', 'confirmed'])
    expect(screen.getByRole('combobox', { name: 'Site' })).toHaveValue('')
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveTextContent('Confirmed, To review')
    expect(screen.queryByRole('button', { name: /^Review/ })).not.toBeInTheDocument()

    const [params] = anomalyRequests('/anomalies')
    expect(params.get('status')).toBe('confirmed,to_review')
    expect(params.has('siteId')).toBe(false)

    const link = within(rows[1]).getByRole('link', { name: 'Cold room' })
    const target = new URL(link.getAttribute('href')!, 'http://localhost')
    expect(target.pathname).toBe(`/devices/${DEVICE}`)
    expect(target.searchParams.get('period')).toBe('custom')
    expect(target.searchParams.get('metrics')).toBe('temperature')
    expect(Date.parse(target.searchParams.get('from')!)).toBe(Date.parse(items[1].startedAt) - 3_600_000)
    expect(Date.parse(target.searchParams.get('to')!)).toBe(Date.parse(items[1].endedAt) + 3_600_000)

    await user.selectOptions(screen.getByRole('combobox', { name: 'Site' }), 'paris-01')
    expect(router.state.location.search).toBe('?siteId=paris-01')
    await waitFor(() => expect(anomalyRequests('/anomalies').some((p) => p.get('siteId') === 'paris-01')).toBe(true))
  })

  it('lets an Editor review an anomaly to review, which then shows as reviewed with its verdict', async () => {
    const reviews: unknown[] = []
    const { user } = renderPortal('/anomalies', EDITOR, { ...ANOMALIES, handlers: anomaliesApi([anomaly(1, 'to_review'), anomaly(2, 'confirmed')], reviews) })

    const [toReview] = await screen.findAllByRole('button', { name: 'Review Temperature (°C) anomaly of Cold room' })
    await user.click(toReview)
    const dialog = await screen.findByRole('dialog', { name: 'Review this anomaly' })
    expect(dialog).toHaveTextContent('Real event · 55 %')
    await user.click(within(dialog).getByRole('button', { name: 'Save review' }))
    expect(await within(dialog).findByText('Choose whether the classification is correct.')).toBeInTheDocument()
    expect(reviews).toEqual([])

    await user.click(within(dialog).getByRole('radio', { name: 'Classification incorrect' }))
    await user.type(within(dialog).getByRole('textbox', { name: /Comment/ }), 'Door left open during a delivery')
    expect(dialog).toHaveTextContent('32/1000 characters')
    await user.click(within(dialog).getByRole('button', { name: 'Save review' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Review this anomaly' })).not.toBeInTheDocument())
    expect(reviews).toEqual([{ verdict: 'incorrect', comment: 'Door left open during a delivery' }])
    expect(await screen.findByRole('status')).toHaveTextContent('Review saved: Temperature (°C) of Cold room, classification incorrect.')
    await waitFor(() =>
      expect(within(screen.getByRole('table', { name: 'Anomalies' })).getAllByRole('row').slice(1).map((r) => r.dataset.status)).toEqual(['confirmed']),
    )

    await user.click(screen.getByRole('combobox', { name: 'Status' }))
    // A multiselect dropdown's options are checkable items.
    await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Reviewed' }))
    const reviewed = await screen.findByText('Classification incorrect')
    const row = reviewed.closest('tr')!
    expect(row.dataset.status).toBe('reviewed')
    expect(within(row).getByText('Reviewed')).toBeInTheDocument()
    expect(within(row).getByText(/by Ed Editor/)).toBeInTheDocument()
    expect(within(row).getByText('“Door left open during a delivery”')).toBeInTheDocument()
  })

  it('shows the API validation message of a field', async () => {
    const { user } = renderPortal('/anomalies', EDITOR, {
      ...ANOMALIES,
      handlers: [
        http.post(`${API}/anomalies/:id/review`, () =>
          HttpResponse.json({ title: 'Bad Request', status: 400, errors: { comment: ['size must be between 0 and 1000'] } }, { status: 400 }),
        ),
        ...anomaliesApi([anomaly(1, 'to_review')]),
      ],
    })

    await user.click(await screen.findByRole('button', { name: /^Review/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Review this anomaly' })
    await user.click(within(dialog).getByRole('radio', { name: 'Classification correct' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save review' }))

    expect(await within(dialog).findByText('size must be between 0 and 1000')).toBeInTheDocument()
  })

  it('does not exist when anomaly detection is disabled', async () => {
    renderPortal('/anomalies', EDITOR)

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Anomalies' })).not.toBeInTheDocument()
  })
})
