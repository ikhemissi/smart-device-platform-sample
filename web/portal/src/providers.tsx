import { FluentProvider, webLightTheme } from '@fluentui/react-components'
import { type QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { ApiClient } from './api/client'
import { ApiContext } from './api/context'
import { type Session, SessionContext } from './auth/session'
import { type Features, FeaturesContext, NO_FEATURES } from './features'

export function PortalProviders(props: {
  session: Session
  api: ApiClient
  queryClient: QueryClient
  features?: Features
  children: ReactNode
}) {
  return (
    <FluentProvider theme={webLightTheme}>
      <FeaturesContext.Provider value={props.features ?? NO_FEATURES}>
        <SessionContext.Provider value={props.session}>
          <ApiContext.Provider value={props.api}>
            <QueryClientProvider client={props.queryClient}>{props.children}</QueryClientProvider>
          </ApiContext.Provider>
        </SessionContext.Provider>
      </FeaturesContext.Provider>
    </FluentProvider>
  )
}
