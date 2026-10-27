import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './context'
import type { DeviceEdit, DeviceQuery } from './types'

export const queryKeys = {
  me: ['me'] as const,
  sites: ['sites'] as const,
  devices: (query: DeviceQuery) => ['devices', 'list', query] as const,
  device: (deviceId: string) => ['devices', 'detail', deviceId] as const,
}

export function useCurrentUser() {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.me, queryFn: () => api.me(), staleTime: 5 * 60_000 })
}

export function useSites(enabled = true) {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.sites, queryFn: () => api.sites(), staleTime: 5 * 60_000, enabled })
}

export function useDevices(query: DeviceQuery) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.devices(query),
    queryFn: () => api.devices(query),
    placeholderData: keepPreviousData,
    // Status and last-seen times change continuously.
    refetchInterval: 60_000,
  })
}

export function useDevice(deviceId: string | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.device(deviceId ?? ''),
    queryFn: () => api.device(deviceId!),
    enabled: !!deviceId,
    staleTime: 0,
  })
}

export function useUpdateDevice(deviceId: string) {
  const api = useApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ etag, edit }: { etag: string; edit: Partial<DeviceEdit> }) => api.updateDevice(deviceId, etag, edit),
    onSuccess: (updated) => {
      client.setQueryData(queryKeys.device(deviceId), updated)
      return client.invalidateQueries({ queryKey: ['devices', 'list'] })
    },
  })
}
