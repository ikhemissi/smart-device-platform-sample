import { InteractionType, type PublicClientApplication } from '@azure/msal-browser'
import { MsalAuthenticationTemplate, MsalProvider } from '@azure/msal-react'
import { FluentProvider, Spinner, webLightTheme } from '@fluentui/react-components'
import { useState } from 'react'
import { RouterProvider, createBrowserRouter } from 'react-router'
import { ApiClient } from './api/client'
import { loginRequest, msalSession } from './auth/msal'
import type { RuntimeConfig } from './config'
import { PortalProviders } from './providers'
import { createQueryClient } from './queryClient'
import { routes } from './routes'
import { StartupError } from './StartupError'

/** Signs the user in (redirect) before rendering the portal. */
export function App({ msal, config }: { msal: PublicClientApplication; config: RuntimeConfig }) {
  return (
    <MsalProvider instance={msal}>
      <MsalAuthenticationTemplate
        interactionType={InteractionType.Redirect}
        authenticationRequest={loginRequest(config)}
        loadingComponent={() => (
          <FluentProvider theme={webLightTheme}>
            <Spinner label="Signing you in…" />
          </FluentProvider>
        )}
        errorComponent={({ error }) => <StartupError error={error} />}
      >
        <Portal msal={msal} config={config} />
      </MsalAuthenticationTemplate>
    </MsalProvider>
  )
}

function Portal({ msal, config }: { msal: PublicClientApplication; config: RuntimeConfig }) {
  const [state] = useState(() => {
    const session = msalSession(msal, config)
    return {
      session,
      api: new ApiClient(config.apiBaseUrl, session),
      queryClient: createQueryClient(),
      router: createBrowserRouter(routes),
    }
  })
  return (
    <PortalProviders
      session={state.session}
      api={state.api}
      queryClient={state.queryClient}
      features={{ assistant: config.assistantEnabled }}
    >
      <RouterProvider router={state.router} />
    </PortalProviders>
  )
}
