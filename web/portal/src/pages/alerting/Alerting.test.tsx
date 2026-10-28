import { screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { Alert, Threshold } from '../../api/types'
import { renderPortal } from '../../test/render'
import { API, EDITOR, READER } from '../../test/server'

// ECharts needs a canvas (the chart is tested in DevicePage.test.tsx and chartOption.test.ts).
vi.mock('../../components/TelemetryChart', () => ({ TelemetryChart: () => <div role="img" aria-label="Chart" /> }))

const DEVICE = 'lyon-01-ruuvi5-001'

const THRESHOLD: Threshold = {
  id: '6f1c2f8e-0000-4000-8000-000000000001',
  deviceId: DEVICE,
  metric: 'temperature',
  unit: '°C',
  min: 2,
  max: 8,
  recipients: ['ops@example.com'],
  enabled: true,
  etag: '"6f1c.1"',
  updatedAt: '2026-10-28T10:00:00Z',
  updatedByName: 'Ed Editor',
  openAlertId: 7,
}

const alert = (id: number, status: Alert['status'], emails: Alert['notifications']): Alert => ({
  id,
  thresholdId: THRESHOLD.id,
  deviceId: DEVICE,
  deviceName: 'Cold room',
  siteId: 'lyon-01',
  metric: 'temperature',
  unit: '°C',
  thresholdMin: 2,
  thresholdMax: 8,
  direction: 'ABOVE',
  status,
  openedAt: new Date(Date.now() - (id === 7 ? 600_000 : 86_400_000)).toISOString(),
  closedAt: status === 'OPEN' ? null : new Date(Date.now() - 80_000_000).toISOString(),
  firstValue: 12,
  worstValue: 12.5,
  lastValue: status === 'OPEN' ? 12.1 : 5,
  lastBreachAt: new Date().toISOString(),
  notifications: emails,
})

const ALERTS = [
  alert(7, 'OPEN', [{ kind: 'ALERT', status: 'SENT', recipients: 1, attempts: 1, createdAt: '', sentAt: '', lastError: null }]),
  alert(3, 'CLOSED', [
    { kind: 'ALERT', status: 'FAILED', recipients: 1, attempts: 9, createdAt: '', sentAt: null, lastError: 'unavailable' },
    { kind: 'RESOLVED', status: 'SENT', recipients: 1, attempts: 1, createdAt: '', sentAt: '', lastError: null },
  ]),
]

const alerting = (thresholds: Threshold[] = [THRESHOLD]) => [
  http.get(`${API}/devices/:id/thresholds`, () => HttpResponse.json(thresholds)),
  http.get(`${API}/devices/:id/alerts`, () => HttpResponse.json(ALERTS)),
]

describe('alerting on the device page', () => {
  it('shows thresholds and alert history to Readers, without threshold actions', async () => {
    renderPortal(`/devices/${DEVICE}`, READER, { handlers: alerting() })

    const thresholds = await screen.findByRole('table', { name: 'Thresholds' })
    expect(within(thresholds).getByText('between 2 °C and 8 °C')).toBeInTheDocument()
    expect(within(thresholds).getByText('Alerting')).toBeInTheDocument()
    expect(within(thresholds).getByText('ops@example.com')).toBeInTheDocument()

    const alerts = await screen.findByRole('table', { name: 'Alerts' })
    const rows = within(alerts).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.dataset.status)).toEqual(['OPEN', 'CLOSED'])
    expect(within(rows[0]).getByText('Open')).toBeInTheDocument()
    expect(within(rows[0]).getByText('above 8 °C, worst 12.5 °C')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Alert failed, back to normal sent')).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: 'Add threshold' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit threshold/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete threshold/ })).not.toBeInTheDocument()
  })

  it('lets Editors add a threshold, checking the range before sending it', async () => {
    const created: unknown[] = []
    const { user } = renderPortal(`/devices/${DEVICE}`, EDITOR, {
      handlers: [
        ...alerting([]),
        http.post(`${API}/devices/:id/thresholds`, async ({ request }) => {
          created.push(await request.json())
          return HttpResponse.json({ ...THRESHOLD, openAlertId: null }, { status: 201 })
        }),
      ],
    })

    await user.click(await screen.findByRole('button', { name: 'Add threshold' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a threshold' })
    await user.type(within(dialog).getByRole('textbox', { name: /Minimum/ }), '8')
    await user.type(within(dialog).getByRole('textbox', { name: /Maximum/ }), '2')
    await user.type(within(dialog).getByRole('textbox', { name: /Email recipients/ }), 'ops@example.com, cold-room@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Add threshold' }))

    expect(await within(dialog).findByText('The minimum must be lower than the maximum.')).toBeInTheDocument()
    expect(created).toEqual([])

    await user.clear(within(dialog).getByRole('textbox', { name: /Minimum/ }))
    await user.type(within(dialog).getByRole('textbox', { name: /Minimum/ }), '2')
    await user.clear(within(dialog).getByRole('textbox', { name: /Maximum/ }))
    await user.type(within(dialog).getByRole('textbox', { name: /Maximum/ }), '8')
    await user.click(within(dialog).getByRole('button', { name: 'Add threshold' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a threshold' })).not.toBeInTheDocument())
    expect(created).toEqual([{ metric: 'temperature', min: 2, max: 8, recipients: ['ops@example.com', 'cold-room@example.com'], enabled: true }])
  })

  it('shows the API validation message of a field', async () => {
    const { user } = renderPortal(`/devices/${DEVICE}`, EDITOR, {
      handlers: [
        ...alerting([]),
        http.post(`${API}/devices/:id/thresholds`, () =>
          HttpResponse.json(
            { title: 'Bad Request', status: 400, detail: 'Invalid request', errors: { metric: ["'temperature' is not reported by device x"] } },
            { status: 400 },
          ),
        ),
      ],
    })

    await user.click(await screen.findByRole('button', { name: 'Add threshold' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a threshold' })
    await user.type(within(dialog).getByRole('textbox', { name: /Maximum/ }), '8')
    await user.type(within(dialog).getByRole('textbox', { name: /Email recipients/ }), 'ops@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Add threshold' }))

    expect(await within(dialog).findByText("'temperature' is not reported by device x")).toBeInTheDocument()
  })

  it('deletes a threshold after confirmation, with its entity tag', async () => {
    let deleted: string | null = null
    const { user } = renderPortal(`/devices/${DEVICE}`, EDITOR, {
      handlers: [
        ...alerting(),
        http.delete(`${API}/devices/:id/thresholds/:threshold`, ({ request }) => {
          deleted = request.headers.get('If-Match')
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })

    const remove = await screen.findByRole('button', { name: 'Delete threshold on Air temperature (°C)' })
    await user.click(remove)
    const confirm = await screen.findByRole('alertdialog', { name: 'Delete this threshold?' })
    expect(confirm).toHaveTextContent('Its open alert is closed without sending an email.')
    await user.click(within(confirm).getByRole('button', { name: 'Delete threshold' }))

    await waitFor(() => expect(deleted).toBe('"6f1c.1"'))
  })
})
