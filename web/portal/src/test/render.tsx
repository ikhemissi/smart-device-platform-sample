import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { HttpHandler } from 'msw'
import { RouterProvider, createMemoryRouter } from 'react-router'
import { vi } from 'vitest'
import { ApiClient } from '../api/client'
import type { CurrentUser } from '../api/types'
import { PortalProviders } from '../providers'
import { createQueryClient } from '../queryClient'
import { routes } from '../routes'
import { API, apiHandlers, requests, resetRequests, server } from './server'

let renders = 0

/**
 * Renders the whole portal at a URL, signed in as the given user, against the fake API. {@code handlers} override
 * the default fake API.
 */
export function renderPortal(
  path: string,
  me: CurrentUser,
  options: { advanceTimers?: (ms: number) => unknown; handlers?: HttpHandler[] } = {},
) {
  const token = `test-token-${++renders}`
  // Overrides first: within one call the first matching handler wins.
  server.use(...(options.handlers ?? []), ...apiHandlers(me, resetRequests(), token))
  const session = { getAccessToken: vi.fn(async () => token), signOut: vi.fn(async () => {}) }
  const queryClient = createQueryClient()
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup(options.advanceTimers ? { advanceTimers: options.advanceTimers } : {})
  const view = render(
    <PortalProviders session={session} api={new ApiClient(API, session)} queryClient={queryClient}>
      <RouterProvider router={router} />
    </PortalProviders>,
  )
  return { ...view, router, session, user }
}

export function deviceRequests(): URLSearchParams[] {
  return requests
    .filter((r) => new URL(r.url).pathname.endsWith('/devices'))
    .map((r) => new URL(r.url).searchParams)
}
