import { FluentProvider, webLightTheme } from '@fluentui/react-components'
import { type QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { ApiClient } from './api/client'
import { ApiContext } from './api/context'
import { type Session, SessionContext } from './auth/session'

export function PortalProviders(props: { session: Session; api: ApiClient; queryClient: QueryClient; children: ReactNode }) {
  return (
    <FluentProvider theme={webLightTheme}>
      <SessionContext.Provider value={props.session}>
        <ApiContext.Provider value={props.api}>
          <QueryClientProvider client={props.queryClient}>{props.children}</QueryClientProvider>
        </ApiContext.Provider>
      </SessionContext.Provider>
    </FluentProvider>
  )
}
