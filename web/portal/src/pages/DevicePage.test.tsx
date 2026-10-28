import { act, screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Series } from '../api/types'
import { renderPortal } from '../test/render'
import { API, READER, latestMeasurements, requests } from '../test/server'

// ECharts needs a canvas; the chart's option is tested separately (chartOption.test.ts).
vi.mock('../components/TelemetryChart', () => ({
  TelemetryChart: ({ series, title }: { series: Series; title: string }) => (
    <div
      role="img"
      aria-label={title}
      data-resolution={series.resolution}
      data-metrics={series.series.map((s) => `${s.metric}:${s.unit}:${s.points.length}`).join(',')}
    />
  ),
}))

const DEVICE = '/devices/lyon-01-ruuvi5-001'
const seriesRequests = () => requests.filter((r) => r.url.includes('/series')).map((r) => new URL(r.url).searchParams)
const latestRequests = () => requests.filter((r) => r.url.includes('/measurements/latest'))

describe('device page', () => {
  afterEach(() => vi.useRealTimers())

  it('opens from the device list with information, chart and latest measurements', async () => {
    const { user, router } = renderPortal('/devices', READER)

    await user.click(await screen.findByRole('link', { name: 'Cold room' }))

    expect(router.state.location.pathname).toBe('/devices/lyon-01-ruuvi5-001')
    expect(await screen.findByRole('heading', { name: 'Cold room', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('lyon-01')).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: /Chart of Air temperature/ })).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Latest measurements' })).toBeInTheDocument()
  })

  it('charts temperature by default over the last 24 hours', async () => {
    renderPortal(DEVICE, READER)

    const chart = await screen.findByRole('img', { name: /Chart of/ })
    expect(chart).toHaveAttribute('data-metrics', 'temperature:°C:12')
    const params = seriesRequests()[0]
    expect(params.get('metrics')).toBe('temperature')
    expect(params.has('to')).toBe(false)
    expect(Date.now() - Date.parse(params.get('from')!)).toBeCloseTo(86_400_000, -4)
    expect(screen.getByRole('button', { name: '24 h' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('adds humidity with its unit to the chart', async () => {
    const { user, router } = renderPortal(DEVICE, READER)
    await screen.findByRole('img', { name: /Chart of/ })

    await user.click(screen.getByRole('combobox', { name: /Metrics/ }))
    await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Relative humidity (%)' }))

    await waitFor(() => expect(screen.getByRole('img', { name: /Chart of/ })).toHaveAttribute('data-metrics', 'temperature:°C:12,humidity:%:12'))
    expect(new URLSearchParams(router.state.location.search).get('metrics')).toBe('temperature,humidity')
  })

  it('switches to 7 days and records the period in the URL', async () => {
    const { user, router } = renderPortal(DEVICE, READER)
    await screen.findByRole('img', { name: /Chart of/ })

    await user.click(screen.getByRole('button', { name: '7 d' }))

    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('period')).toBe('7d'))
    await waitFor(() => expect(Date.now() - Date.parse(seriesRequests().at(-1)!.get('from')!)).toBeCloseTo(7 * 86_400_000, -4))
    expect(screen.getByRole('button', { name: '7 d' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('restores metrics and a custom period from the URL', async () => {
    renderPortal(`${DEVICE}?metrics=humidity&period=custom&from=2026-10-20T00:00:00.000Z&to=2026-10-21T00:00:00.000Z`, READER)

    await screen.findByRole('img', { name: /Chart of Relative humidity/ })
    const params = seriesRequests()[0]
    expect([params.get('metrics'), params.get('from'), params.get('to')]).toEqual([
      'humidity',
      '2026-10-20T00:00:00.000Z',
      '2026-10-21T00:00:00.000Z',
    ])
  })

  it('lists the latest 20 measurements, most recent first, with units in the headers', async () => {
    renderPortal(DEVICE, READER)

    const table = await screen.findByRole('table', { name: 'Latest measurements' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(21)
    const headers = within(rows[0]).getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual(['Measured', 'humidity (%)', 'pressure (hPa)', 'temperature (°C)', 'Format'])
    const times = rows.slice(1).map((r) => Date.parse(within(r).getAllByRole('time')[0].getAttribute('datetime')!))
    expect([...times].sort((a, b) => b - a)).toEqual(times)
    expect(rows[1]).toHaveTextContent('21')
  })

  it('refreshes every 30 seconds, highlights new rows and pauses while the tab is hidden', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    // Fixed timestamps: the second response adds exactly one newer measurement.
    const history = latestMeasurements(21, 0)
    let newest = 1
    renderPortal(DEVICE, READER, {
      advanceTimers: vi.advanceTimersByTime,
      handlers: [http.get(`${API}/devices/:id/measurements/latest`, () => HttpResponse.json(history.slice(newest, newest + 20)))],
    })
    const table = await screen.findByRole('table', { name: 'Latest measurements' })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(21))
    const seriesBefore = seriesRequests().length

    newest = 0
    await act(() => vi.advanceTimersByTimeAsync(30_000))

    await waitFor(() => expect(table.querySelectorAll('[data-fresh]')).toHaveLength(1))
    expect(seriesRequests().length).toBeGreaterThan(seriesBefore)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    const latestHidden = latestRequests().length
    await act(() => vi.advanceTimersByTimeAsync(90_000))
    expect(latestRequests().length).toBe(latestHidden)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
  })

  it('states that a period has no data and suggests a longer one', async () => {
    const handlers = [
      http.get(`${API}/devices/:id/series`, ({ request }) =>
        HttpResponse.json({ ...emptySeries(new URL(request.url)) }),
      ),
    ]
    const { user, router } = renderPortal(`${DEVICE}?period=1h`, READER, { handlers })

    expect(await screen.findByText(/No data exists for this period/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try a longer period' }))

    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('period')).toBe('30d'))
  })

  it('offers a retry when the history cannot be loaded', async () => {
    let calls = 0
    const handlers = [
      http.get(`${API}/devices/:id/series`, ({ request }) => {
        calls++
        return calls === 1
          ? HttpResponse.json({ title: 'Service Unavailable', status: 503 }, { status: 503 })
          : HttpResponse.json(emptySeries(new URL(request.url)))
      }),
    ]
    const { user } = renderPortal(DEVICE, READER, { handlers })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The history could not be loaded')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText(/No data exists for this period/)).toBeInTheDocument()
  })

  it('shows a not-found page for an unknown device', async () => {
    renderPortal('/devices/does-not-exist', READER)

    expect(await screen.findByRole('heading', { name: 'Device not found' })).toBeInTheDocument()
  })
})

function emptySeries(url: URL): Series {
  return {
    deviceId: 'd',
    from: url.searchParams.get('from')!,
    to: new Date().toISOString(),
    resolution: 'raw',
    series: (url.searchParams.get('metrics') ?? '').split(',').map((metric) => ({ metric, unit: null, points: [] })),
  }
}
