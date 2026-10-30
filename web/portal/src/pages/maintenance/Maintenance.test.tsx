import { screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { DeviceMaintenance, MaintenanceItem, MaintenanceStatus } from '../../api/types'
import { NO_FEATURES } from '../../features'
import { renderPortal } from '../../test/render'
import { API, EDITOR, READER, requests } from '../../test/server'

vi.mock('../../components/TelemetryChart', () => ({ TelemetryChart: () => <div role="img" aria-label="Chart" /> }))

const MAINTENANCE = { features: { ...NO_FEATURES, maintenance: true } }
const DEVICE = 'paris-01-cayenne-001'

const item = (id: number, status: MaintenanceStatus, changes: Partial<MaintenanceItem> = {}): MaintenanceItem => ({
  id,
  deviceId: DEVICE,
  deviceName: 'Cold room',
  siteId: 'paris-01',
  status,
  probability: 0.82,
  reasons: [{ rule: 'battery_days_left', value: 5.2, threshold: 7, unit: 'days', level: 2.61, level_unit: 'V' }],
  battery: { metric: 'battery_voltage', unit: 'V', level: 2.61, slope_per_day: -0.021, days_left: 5.2, since: '2026-10-20T00:00:00Z', cutoff: 2.5 },
  workOrder: 'Replace the battery of Cold room (paris-01) this week: 2.61 V, about 5 days left.',
  workOrderModel: 'gpt-6-luna',
  workOrderAt: new Date().toISOString(),
  openedAt: new Date(Date.now() - 86_400_000).toISOString(),
  updatedAt: new Date().toISOString(),
  resolvedAt: null,
  statusBy: null,
  statusAt: null,
  statusComment: null,
  ...changes,
})

const listRequests = () => requests.filter((r) => new URL(r.url).pathname === '/api/v1/maintenance').map((r) => new URL(r.url).searchParams)

/** Fake queue filtering by status and site like the API, and the status endpoint updating it. */
function maintenanceApi(items: MaintenanceItem[], changes: unknown[] = []) {
  return [
    http.get(`${API}/maintenance`, ({ request }) => {
      requests.push(request)
      const q = new URL(request.url).searchParams
      const statuses = (q.get('status') ?? 'open,planned').split(',')
      return HttpResponse.json(items.filter((i) => statuses.includes(i.status) && (!q.get('siteId') || i.siteId === q.get('siteId'))))
    }),
    http.post(`${API}/maintenance/:id/status`, async ({ request, params }) => {
      const body = (await request.json()) as { status: 'planned' | 'done'; comment: string | null }
      changes.push({ id: Number(params.id), ...body })
      const target = items.find((i) => i.id === Number(params.id))!
      if (target.status === 'done' || target.status === 'resolved') return HttpResponse.json({ title: 'Conflict', status: 409 }, { status: 409 })
      Object.assign(target, { status: body.status, statusBy: 'Ed Editor', statusAt: new Date().toISOString(), statusComment: body.comment })
      return HttpResponse.json(target)
    }),
  ]
}

describe('maintenance page', () => {
  it('lists the queue most likely first with probability, days left, reasons and work order, without actions for Readers', async () => {
    renderPortal('/maintenance', READER, {
      ...MAINTENANCE,
      handlers: maintenanceApi([
        item(1, 'open'),
        item(2, 'planned', {
          deviceId: 'lyon-01-bthome-001',
          deviceName: null,
          siteId: 'lyon-01',
          probability: 0.64,
          battery: null,
          reasons: [{ rule: 'silent', value: 42, threshold: 15, unit: 'minutes' }],
          workOrder: null,
          statusBy: 'Ed Editor',
          statusAt: new Date().toISOString(),
          statusComment: 'Tuesday morning',
        }),
      ]),
    })

    const rows = within(await screen.findByRole('table', { name: 'Maintenance queue' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.dataset.status)).toEqual(['open', 'planned'])
    expect(within(rows[0]).getByRole('link', { name: 'Cold room' })).toHaveAttribute('href', `/devices/${DEVICE}`)
    expect(within(rows[0]).getByText('82 %')).toBeInTheDocument()
    expect(within(rows[0]).getByText('5.2 days')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Battery runs out in 5.2 days (2.61 V)')).toBeInTheDocument()
    expect(within(rows[0]).getByText(/Replace the battery of Cold room/)).toBeInTheDocument()
    expect(within(rows[1]).getByText('No battery reported')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Silent for 42 min')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Not written yet')).toBeInTheDocument()
    expect(within(rows[1]).getByText(/by Ed Editor/)).toBeInTheDocument()
    expect(within(rows[1]).getByText('“Tuesday morning”')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Mark the visit/ })).not.toBeInTheDocument()
    expect(listRequests()[0].get('status')).toBe('open,planned')
  })

  it('filters by site and status through the URL', async () => {
    const { user, router } = renderPortal('/maintenance', READER, { ...MAINTENANCE, handlers: maintenanceApi([item(1, 'open')]) })

    await screen.findByRole('table', { name: 'Maintenance queue' })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Site' }), 'lyon-01')
    expect(await screen.findByText('No device matches these filters.')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?siteId=lyon-01')
    await waitFor(() => expect(listRequests().at(-1)!.get('siteId')).toBe('lyon-01'))
  })

  it('lets an Editor plan a visit, then close it with a comment', async () => {
    const changes: unknown[] = []
    const items = [item(1, 'open')]
    const { user } = renderPortal('/maintenance', EDITOR, { ...MAINTENANCE, handlers: maintenanceApi(items, changes) })

    const table = await screen.findByRole('table', { name: 'Maintenance queue' })
    await user.click(within(table).getByRole('button', { name: 'Mark the visit to Cold room planned' }))
    let dialog = await screen.findByRole('dialog', { name: 'Plan this visit' })
    await user.click(within(dialog).getByRole('button', { name: 'Mark planned' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Cold room: visit planned.')
    await waitFor(() => expect(within(table).getAllByRole('row')[1].dataset.status).toBe('planned'))
    expect(within(table).queryByRole('button', { name: /planned$/ })).not.toBeInTheDocument()

    await user.click(within(table).getByRole('button', { name: 'Mark the visit to Cold room done' }))
    dialog = await screen.findByRole('dialog', { name: 'Close this visit' })
    await user.type(within(dialog).getByRole('textbox', { name: /Comment/ }), 'Battery replaced')
    expect(dialog).toHaveTextContent('16/1000 characters')
    await user.click(within(dialog).getByRole('button', { name: 'Mark done' }))

    expect(await screen.findByText('No device matches these filters.')).toBeInTheDocument()
    expect(changes).toEqual([
      { id: 1, status: 'planned', comment: null },
      { id: 1, status: 'done', comment: 'Battery replaced' },
    ])
  })

  it('says when an item was closed meanwhile', async () => {
    const items = [item(1, 'open')]
    const { user } = renderPortal('/maintenance', EDITOR, { ...MAINTENANCE, handlers: maintenanceApi(items) })

    const table = await screen.findByRole('table', { name: 'Maintenance queue' })
    items[0].status = 'resolved'
    await user.click(within(table).getByRole('button', { name: 'Mark the visit to Cold room done' }))
    const dialog = await screen.findByRole('dialog', { name: 'Close this visit' })
    await user.click(within(dialog).getByRole('button', { name: 'Mark done' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('This item is already closed.')
  })

  it('is not found, without navigation entry, when maintenance is disabled', async () => {
    renderPortal('/maintenance', READER)

    expect(await screen.findByRole('heading', { name: /not found/i })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Maintenance' })).not.toBeInTheDocument()
  })
})

describe('battery and maintenance card', () => {
  const deviceApi = (body: Omit<DeviceMaintenance, 'deviceId'>) =>
    http.get(`${API}/devices/:id/maintenance`, ({ request, params }) => {
      requests.push(request)
      return HttpResponse.json({ deviceId: params.id, ...body })
    })
  const assessment = {
    assessedAt: new Date(Date.now() - 20 * 60_000).toISOString(),
    battery: item(1, 'open').battery,
    signals: {},
    probability: 0.82,
    decisionModel: 'decision-1',
    decisionModelVersion: '1',
  }

  it('shows the battery forecast, the probability and the queue item', async () => {
    renderPortal(`/devices/${DEVICE}`, READER, { ...MAINTENANCE, handlers: [deviceApi({ enabled: true, assessment, item: item(1, 'open') })] })

    expect(await screen.findByRole('heading', { name: 'Battery and maintenance' })).toBeInTheDocument()
    expect(await screen.findByText('2.61 V')).toBeInTheDocument()
    expect(screen.getByText('-0.021 V/day')).toBeInTheDocument()
    expect(screen.getAllByText('5.2 days').length).toBeGreaterThan(0)
    expect(screen.getAllByText('82 %').length).toBeGreaterThan(0)
    const row = within(screen.getByRole('table', { name: 'Maintenance item' })).getAllByRole('row')[1]
    expect(within(row).getByText('Open')).toBeInTheDocument()
  })

  it('shows a device out of the queue and one without battery', async () => {
    renderPortal(`/devices/${DEVICE}`, READER, {
      ...MAINTENANCE,
      handlers: [deviceApi({ enabled: true, assessment: { ...assessment, battery: null, probability: 0.12 }, item: null })],
    })

    expect(await screen.findByText('Not in the maintenance queue.')).toBeInTheDocument()
    expect(screen.getAllByText('No battery reported').length).toBeGreaterThan(0)
    expect(screen.getByText('12 %')).toBeInTheDocument()
  })

  it('says when the site is not assessed', async () => {
    renderPortal(`/devices/${DEVICE}`, READER, MAINTENANCE)

    expect(await screen.findByText('Maintenance is not assessed for this site.')).toBeInTheDocument()
  })

  it('has no card when maintenance is disabled', async () => {
    renderPortal(`/devices/${DEVICE}`, READER)

    expect(await screen.findByRole('heading', { name: 'Alerts' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Battery and maintenance' })).not.toBeInTheDocument()
    expect(requests.some((r) => new URL(r.url).pathname.includes('maintenance'))).toBe(false)
  })
})
