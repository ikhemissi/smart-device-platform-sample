import { act, screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deviceRequests, renderPortal } from '../test/render'
import { API, EDITOR, READER } from '../test/server'
import { SEARCH_DEBOUNCE_MS } from './DeviceListPage'

const rows = () => screen.getAllByRole('row').slice(1)

describe('device list', () => {
  afterEach(() => vi.useRealTimers())

  it('shows devices with name or id, site, format, times and status', async () => {
    renderPortal('/devices', READER)

    const cold = (await screen.findByText('Cold room')).closest('tr')!
    expect(cold).toHaveTextContent('lyon-01-ruuvi5-001')
    expect(cold).toHaveTextContent('ruuvi-df5')
    expect(within(cold).getByText('Reporting')).toBeInTheDocument()
    expect(within(cold).getAllByRole('time')[1]).toHaveTextContent(/minute ago/)

    const unnamed = screen.getByText('lyon-01-bthome-001').closest('tr')!
    expect(within(unnamed).getByText('Silent')).toBeInTheDocument()
    expect(rows()).toHaveLength(3)
    expect(screen.getByText(/1–3 of 3 devices/)).toBeInTheDocument()
  })

  it('narrows the list after a short debounce and puts the search term in the URL', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { user, router } = renderPortal('/devices', READER, { advanceTimers: vi.advanceTimersByTime })
    await screen.findByText('Cold room')

    await user.type(screen.getByRole('searchbox'), 'cold')
    expect(deviceRequests().some((p) => p.get('search') === 'c')).toBe(false)
    await act(() => vi.advanceTimersByTimeAsync(SEARCH_DEBOUNCE_MS + 50))

    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(screen.getByText('Cold room')).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get('search')).toBe('cold')
    expect(deviceRequests().filter((p) => p.has('search')).map((p) => p.get('search'))).toEqual(['cold'])
  })

  it('restores filter and page from a shared link', async () => {
    renderPortal('/devices?siteId=lyon-01&page=2', READER)

    await waitFor(() => expect(deviceRequests().length).toBeGreaterThan(0))
    const params = deviceRequests()[0]
    expect(params.get('siteId')).toBe('lyon-01')
    expect(params.get('page')).toBe('2')
    expect(await screen.findByRole('combobox', { name: 'Site' })).toHaveValue('lyon-01')
  })

  it('filters by site and resets to the first page', async () => {
    const { user, router } = renderPortal('/devices?page=3', READER)
    await screen.findByText('Cold room')
    await screen.findByRole('option', { name: 'paris-01 (1)' })

    await user.selectOptions(screen.getByRole('combobox', { name: 'Site' }), 'paris-01')

    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(router.state.location.search).toBe('?siteId=paris-01')
  })

  it('sorts by a column, toggling the direction', async () => {
    const { user, router } = renderPortal('/devices', READER)
    await screen.findByText('Cold room')

    const lastSeen = () => screen.getByRole('columnheader', { name: /Last seen/ })
    expect(lastSeen().getAttribute('aria-sort') ?? 'none').toBe('none')
    await user.click(within(lastSeen()).getByRole('button'))
    await waitFor(() => expect(lastSeen()).toHaveAttribute('aria-sort', 'ascending'))
    await user.click(within(lastSeen()).getByRole('button'))

    await waitFor(() => expect(lastSeen()).toHaveAttribute('aria-sort', 'descending'))
    const params = new URLSearchParams(router.state.location.search)
    expect([params.get('sort'), params.get('order')]).toEqual(['lastSeen', 'desc'])
    expect(deviceRequests().at(-1)!.get('order')).toBe('desc')
  })

  it('pages through results', async () => {
    const handlers = [
      http.get(`${API}/devices`, ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? 1)
        return HttpResponse.json({ items: [], page, pageSize: 25, total: 60 })
      }),
    ]
    const { user, router } = renderPortal('/devices', READER, { handlers })

    expect(await screen.findByText(/page 1 of 3/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText(/26–50 of 60 devices/)).toBeInTheDocument()
    expect(router.state.location.search).toBe('?page=2')
  })

  it('shows an empty state with a way to clear the filters', async () => {
    const { user } = renderPortal('/devices?search=nothing-matches', READER)

    expect(await screen.findByText('No devices match these filters.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))

    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(screen.getByRole('searchbox')).toHaveValue('')
  })

  it('explains that data could not be loaded and offers a retry', async () => {
    let calls = 0
    const handlers = [
      http.get(`${API}/devices`, () => {
        calls++
        return calls === 1
          ? HttpResponse.json({ title: 'Internal Server Error', status: 500 }, { status: 500 })
          : HttpResponse.json({ items: [], page: 1, pageSize: 25, total: 0 })
      }),
    ]
    const { user } = renderPortal('/devices', READER, { handlers })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The devices could not be loaded')
    expect(alert).toHaveTextContent('temporarily unavailable')
    expect(alert).not.toHaveTextContent('500')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('No device has reported data yet.')).toBeInTheDocument()
  })

  it('shows no edit action to a Reader', async () => {
    renderPortal('/devices', READER)
    await screen.findByText('Cold room')

    expect(screen.queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument()
  })

  it('shows an edit action per device to an Editor', async () => {
    renderPortal('/devices', EDITOR)
    await screen.findByText('Cold room')

    expect(screen.getByRole('button', { name: 'Edit Cold room' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit lyon-01-bthome-001' })).toBeInTheDocument()
  })
})
