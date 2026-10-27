import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderPortal, deviceRequests } from '../test/render'
import { NO_ROLE, READER, requests } from '../test/server'

describe('portal shell', () => {
  it('opens on the device list with the user name in the header', async () => {
    const { router } = renderPortal('/', READER)

    expect(await screen.findByRole('heading', { name: 'Devices', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('banner')).toHaveTextContent('Rita Reader')
    expect(router.state.location.pathname).toBe('/devices')
    expect(screen.getByRole('link', { name: 'Devices' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows the access-denied page without requesting data when the user has no role', async () => {
    renderPortal('/devices', NO_ROLE)

    expect(await screen.findByRole('heading', { name: /do not have access/i })).toBeInTheDocument()
    expect(screen.getByText('Query device data')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Devices' })).not.toBeInTheDocument()
    expect(deviceRequests()).toHaveLength(0)
    expect(requests.map((r) => new URL(r.url).pathname)).toEqual(['/api/v1/me'])
  })

  it('signs out', async () => {
    const { user, session } = renderPortal('/devices', READER)

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(session.signOut).toHaveBeenCalled()
  })

  it('sends the access token to the API', async () => {
    renderPortal('/devices', READER)

    await waitFor(() => expect(requests.length).toBeGreaterThan(0))
    expect(requests[0].headers.get('Authorization')).toMatch(/^Bearer test-token-\d+$/)
  })

  it('shows a page-not-found message for unknown pages', async () => {
    renderPortal('/unknown', READER)

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
