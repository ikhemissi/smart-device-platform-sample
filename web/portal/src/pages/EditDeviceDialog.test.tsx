import { screen, waitFor, within } from '@testing-library/react'
import { type HttpHandler, HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import type { DeviceDetails } from '../api/types'
import { renderPortal } from '../test/render'
import { API, DEVICES, EDITOR, details } from '../test/server'

const device = DEVICES[0]

async function openEditor(handlers: HttpHandler[] = []) {
  const view = renderPortal('/devices', EDITOR, { handlers })
  await view.user.click(await screen.findByRole('button', { name: 'Edit Cold room' }))
  const dialog = await screen.findByRole('dialog', { name: 'Edit Cold room' })
  await waitFor(() => expect(within(dialog).getByRole('textbox', { name: /Display name/ })).toHaveValue('Cold room'))
  return { ...view, dialog }
}

describe('edit device dialog', () => {
  it('saves the change with the entity tag and closes', async () => {
    let sent: { ifMatch: string | null; contentType: string | null; body: unknown } | undefined
    const handlers = [
      http.patch(`${API}/devices/:id`, async ({ request }) => {
        sent = { ifMatch: request.headers.get('If-Match'), contentType: request.headers.get('Content-Type'), body: await request.json() }
        return HttpResponse.json(details(device, { displayName: 'Freezer' }), { headers: { ETag: '"v2"' } })
      }),
    ]
    const { user, dialog } = await openEditor(handlers)

    const name = within(dialog).getByRole('textbox', { name: /Display name/ })
    await user.clear(name)
    await user.type(name, '  Freezer ')
    await user.type(within(dialog).getByRole('textbox', { name: /Latitude/ }), '45,76')
    await user.type(within(dialog).getByRole('textbox', { name: /Longitude/ }), '4.83')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(sent).toEqual({
      ifMatch: '"v1"',
      contentType: 'application/merge-patch+json',
      body: { displayName: 'Freezer', description: null, location: { latitude: 45.76, longitude: 4.83, label: null } },
    })
  })

  it('validates inputs like the API before sending', async () => {
    let patched = false
    const handlers = [http.patch(`${API}/devices/:id`, () => { patched = true; return HttpResponse.json({}) })]
    const { user, dialog } = await openEditor(handlers)

    await user.type(within(dialog).getByRole('textbox', { name: /Display name/ }), 'x'.repeat(95))
    await user.type(within(dialog).getByRole('textbox', { name: /Latitude/ }), '91')
    await user.type(within(dialog).getByRole('textbox', { name: /Location label/ }), 'Lyon')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText(/Use at most 100 characters/)).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a number between -90 and 90.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter the longitude to set a location.')).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox', { name: /Display name/ })).toHaveAttribute('aria-invalid', 'true')
    expect(patched).toBe(false)
  })

  it('explains a concurrent change and shows the latest values', async () => {
    let version = 1
    const latest: DeviceDetails = details(device, { displayName: 'Cold room A', description: 'Renamed by a colleague' })
    const handlers = [
      http.get(`${API}/devices/:id`, () =>
        version === 1
          ? HttpResponse.json(details(device), { headers: { ETag: '"v1"' } })
          : HttpResponse.json(latest, { headers: { ETag: '"v2"' } }),
      ),
      http.patch(`${API}/devices/:id`, () => {
        version = 2
        return HttpResponse.json({ title: 'Precondition Failed', status: 412 }, { status: 412 })
      }),
    ]
    const { user, dialog } = await openEditor(handlers)

    await user.type(within(dialog).getByRole('textbox', { name: /Description/ }), 'My change')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('changed by someone else')
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: /Display name/ })).toHaveValue('Cold room A'))
    expect(within(dialog).getByRole('textbox', { name: /Description/ })).toHaveValue('Renamed by a colleague')
  })

  it('shows API validation errors on the fields', async () => {
    const handlers = [
      http.patch(`${API}/devices/:id`, () =>
        HttpResponse.json(
          { title: 'Bad Request', status: 400, errors: { 'location.longitude': ['must be between -180 and 180'] } },
          { status: 400 },
        ),
      ),
    ]
    const { user, dialog } = await openEditor(handlers)

    await user.type(within(dialog).getByRole('textbox', { name: /Latitude/ }), '1')
    await user.type(within(dialog).getByRole('textbox', { name: /Longitude/ }), '2')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText('must be between -180 and 180')).toBeInTheDocument()
  })

  it('cancels without saving', async () => {
    const { user, dialog } = await openEditor()

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
