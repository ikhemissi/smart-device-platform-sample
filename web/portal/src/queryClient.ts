import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api/client'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Retry only failures that may be transient (network, 5xx, throttling).
        retry: (failures, error) =>
          failures < 2 && (!(error instanceof ApiError) || error.status === 0 || error.status === 429 || error.status >= 500),
        refetchOnWindowFocus: false,
      },
    },
  })
}
