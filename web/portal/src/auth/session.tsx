import { createContext, useContext } from 'react'

/** What the portal needs from the sign-in session; implemented with MSAL in the app and faked in tests. */
export interface Session {
  /** Access token for the platform API, renewed silently; re-prompts for sign-in only when renewal fails. */
  getAccessToken(): Promise<string>
  signOut(): Promise<void>
}

export const SessionContext = createContext<Session | null>(null)

export function useSession(): Session {
  const session = useContext(SessionContext)
  if (!session) {
    throw new Error('useSession must be used inside a SessionContext provider')
  }
  return session
}
