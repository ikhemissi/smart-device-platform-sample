import {
  type AccountInfo,
  EventType,
  type EventMessage,
  type AuthenticationResult,
  InteractionRequiredAuthError,
  PublicClientApplication,
} from '@azure/msal-browser'
import type { RuntimeConfig } from '../config'
import type { Session } from './session'

/** Creates and initialises MSAL (authorization code flow with PKCE, redirect) and completes a pending redirect. */
export async function createMsal(config: RuntimeConfig): Promise<PublicClientApplication> {
  const msal = new PublicClientApplication({
    auth: {
      clientId: config.clientId,
      authority: `https://login.microsoftonline.com/${config.tenantId}`,
      redirectUri: `${window.location.origin}/`,
      postLogoutRedirectUri: `${window.location.origin}/`,
    },
    cache: { cacheLocation: 'sessionStorage' },
  })
  await msal.initialize()
  msal.addEventCallback((event: EventMessage) => {
    if (event.eventType === EventType.LOGIN_SUCCESS && event.payload) {
      msal.setActiveAccount((event.payload as AuthenticationResult).account)
    }
  })
  const result = await msal.handleRedirectPromise()
  const account: AccountInfo | undefined = result?.account ?? msal.getActiveAccount() ?? msal.getAllAccounts()[0]
  if (account) {
    msal.setActiveAccount(account)
  }
  return msal
}

export function loginRequest(config: RuntimeConfig) {
  return { scopes: [config.apiScope] }
}

export function msalSession(msal: PublicClientApplication, config: RuntimeConfig): Session {
  const request = loginRequest(config)
  return {
    async getAccessToken() {
      const account = msal.getActiveAccount() ?? undefined
      try {
        return (await msal.acquireTokenSilent({ ...request, account })).accessToken
      } catch (error) {
        if (error instanceof InteractionRequiredAuthError) {
          await msal.acquireTokenRedirect({ ...request, account })
        }
        throw error
      }
    },
    signOut() {
      return msal.logoutRedirect({ account: msal.getActiveAccount() ?? undefined })
    },
  }
}
