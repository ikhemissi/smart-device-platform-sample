import { ApiError } from './client'

/** Non-technical explanation of a failed request. */
export function explain(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return 'The platform could not be reached. Check your connection and try again.'
    }
    if (error.status === 403) {
      return 'You do not have the permission required for this action.'
    }
    if (error.status === 429) {
      return 'Too many requests were sent in a short time. Wait a moment and try again.'
    }
    if (error.status >= 500) {
      return 'The platform is temporarily unavailable. Try again in a moment.'
    }
  }
  return 'Something went wrong. Try again, and contact your administrator if the problem persists.'
}
