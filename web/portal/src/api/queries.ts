import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './context'
import type { DeviceEdit, DeviceQuery } from './types'

export const queryKeys = {
  me: ['me'] as const,
  sites: ['sites'] as const,
  devices: (query: DeviceQuery) => ['devices', 'list', query] as const,
  device: (deviceId: string) => ['devices', 'detail', deviceId] as const,
  allSiteCertificates: ['site-certificates'] as const,
  siteCertificates: (siteId: string) => ['site-certificates', siteId] as const,
  siteAudit: (siteId: string) => ['site-audit', siteId] as const,
  latest: (deviceId: string) => ['devices', 'latest', deviceId] as const,
  metrics: (deviceId: string) => ['devices', 'metrics', deviceId] as const,
  series: (deviceId: string, metrics: string[], period: string, from?: string, to?: string) =>
    ['devices', 'series', deviceId, metrics, period, from, to] as const,
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

/** While the page is visible, history refreshes every 30 s (TanStack Query pauses intervals in hidden tabs). */
export const HISTORY_REFRESH_MS = 30_000

export function useLatestMeasurements(deviceId: string) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.latest(deviceId),
    queryFn: () => api.latestMeasurements(deviceId, 20),
    refetchInterval: HISTORY_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

export function useDeviceMetrics(deviceId: string) {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.metrics(deviceId), queryFn: () => api.deviceMetrics(deviceId), staleTime: 5 * 60_000 })
}

export interface SeriesRequest {
  deviceId: string
  metrics: string[]
  /** Preset key, used to recompute the window on every refresh, or `custom`. */
  period: string
  /** Window length for presets, in milliseconds. */
  presetMs?: number
  from?: Date
  to?: Date
  maxPoints: number
}

export function useSeries(request: SeriesRequest) {
  const api = useApi()
  const live = request.period !== 'custom'
  return useQuery({
    queryKey: queryKeys.series(request.deviceId, request.metrics, request.period, request.from?.toISOString(), request.to?.toISOString()),
    queryFn: () =>
      live
        ? api.series(request.deviceId, request.metrics, new Date(Date.now() - request.presetMs!), undefined, request.maxPoints)
        : api.series(request.deviceId, request.metrics, request.from!, request.to, request.maxPoints),
    enabled: request.metrics.length > 0 && (live || (!!request.from && !!request.to)),
    placeholderData: keepPreviousData,
    refetchInterval: live ? HISTORY_REFRESH_MS : false,
    refetchIntervalInBackground: false,
  })
}

export function useAllSiteCertificates() {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.allSiteCertificates, queryFn: () => api.allSiteCertificates() })
}

export function useSiteCertificates(siteId: string) {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.siteCertificates(siteId), queryFn: () => api.siteCertificates(siteId) })
}

export function useSiteAudit(siteId: string) {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.siteAudit(siteId), queryFn: () => api.siteAudit(siteId) })
}

/** After a trust change: certificates, audit, site list and device statuses may all have changed. */
function useInvalidateTrust() {
  const client = useQueryClient()
  return (siteId: string) =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['site-certificates'] }),
      client.invalidateQueries({ queryKey: queryKeys.siteAudit(siteId) }),
      client.invalidateQueries({ queryKey: queryKeys.sites }),
      client.invalidateQueries({ queryKey: ['devices', 'list'] }),
    ])
}

export function useCreateSite() {
  const api = useApi()
  const invalidate = useInvalidateTrust()
  return useMutation({
    mutationFn: ({ siteId, displayName }: { siteId: string; displayName: string }) => api.createSite(siteId, displayName),
    onSuccess: (_, { siteId }) => invalidate(siteId),
  })
}

export function useAddSiteCertificate(siteId: string) {
  const api = useApi()
  const invalidate = useInvalidateTrust()
  return useMutation({ mutationFn: (file: File) => api.addSiteCertificate(siteId, file), onSuccess: () => invalidate(siteId) })
}

export function useRemoveSiteCertificate(siteId: string) {
  const api = useApi()
  const invalidate = useInvalidateTrust()
  return useMutation({
    mutationFn: (fingerprint: string) => api.removeSiteCertificate(siteId, fingerprint),
    onSuccess: () => invalidate(siteId),
  })
}
