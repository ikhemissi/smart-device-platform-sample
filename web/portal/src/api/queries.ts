import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './context'
import type { AnomalyQuery, AnomalyStatus, DeviceEdit, DeviceQuery, MaintenanceQuery, ReviewVerdict, SiteDigest, Threshold, ThresholdInput } from './types'

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
  thresholds: (deviceId: string) => ['devices', 'thresholds', deviceId] as const,
  deviceAlerts: (deviceId: string) => ['devices', 'alerts', deviceId] as const,
  anomalies: (query: AnomalyQuery) => ['anomalies', 'list', query] as const,
  deviceAnomalies: (deviceId: string, status: AnomalyStatus[], period: string, from?: string, to?: string) =>
    ['anomalies', 'device', deviceId, status, period, from, to] as const,
  siteDigest: (siteId: string) => ['site-digest', siteId] as const,
  maintenance: (query: MaintenanceQuery) => ['maintenance', 'list', query] as const,
  deviceMaintenance: (deviceId: string) => ['maintenance', 'device', deviceId] as const,
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

// --- Alerting (M5) ---------------------------------------------------------------------------------------------

/** Alerts open and close as measurements arrive: refreshed like the latest measurements. */
const ALERTS_REFRESH_MS = 30_000

export function useThresholds(deviceId: string) {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.thresholds(deviceId), queryFn: () => api.thresholds(deviceId), refetchInterval: ALERTS_REFRESH_MS })
}

export function useDeviceAlerts(deviceId: string) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.deviceAlerts(deviceId),
    queryFn: () => api.deviceAlerts(deviceId),
    refetchInterval: ALERTS_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

function useInvalidateAlerting(deviceId: string) {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: queryKeys.thresholds(deviceId) }),
      client.invalidateQueries({ queryKey: queryKeys.deviceAlerts(deviceId) }),
    ])
}

/** Creates a threshold, or replaces `existing` (with its entity tag). */
export function useSaveThreshold(deviceId: string) {
  const api = useApi()
  const invalidate = useInvalidateAlerting(deviceId)
  return useMutation({
    mutationFn: ({ existing, input }: { existing?: Threshold; input: ThresholdInput }) =>
      existing ? api.updateThreshold(deviceId, existing, input) : api.createThreshold(deviceId, input),
    onSettled: invalidate,
  })
}

export function useDeleteThreshold(deviceId: string) {
  const api = useApi()
  const invalidate = useInvalidateAlerting(deviceId)
  return useMutation({ mutationFn: (threshold: Threshold) => api.deleteThreshold(deviceId, threshold), onSettled: invalidate })
}

// --- Anomaly detection (M7) ------------------------------------------------------------------------------------

/** The detector runs every few minutes: lists refresh every minute while visible. */
const ANOMALIES_REFRESH_MS = 60_000

export function useAnomalies(query: AnomalyQuery) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.anomalies(query),
    queryFn: () => api.anomalies(query),
    placeholderData: keepPreviousData,
    refetchInterval: ANOMALIES_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

export interface DeviceAnomaliesRequest {
  deviceId: string
  status: AnomalyStatus[]
  /** Preset key of the device page, used to recompute the window on every refresh, or `custom`. */
  period: string
  presetMs?: number
  from?: Date
  to?: Date
}

/** Anomalies of a device over the device page's period (like its history). */
export function useDeviceAnomalies(request: DeviceAnomaliesRequest) {
  const api = useApi()
  const live = request.period !== 'custom'
  return useQuery({
    queryKey: queryKeys.deviceAnomalies(request.deviceId, request.status, request.period, request.from?.toISOString(), request.to?.toISOString()),
    queryFn: () =>
      live
        ? api.deviceAnomalies(request.deviceId, request.status, new Date(Date.now() - request.presetMs!))
        : api.deviceAnomalies(request.deviceId, request.status, request.from!, request.to),
    enabled: live ? request.presetMs !== undefined : !!request.from && !!request.to,
    placeholderData: keepPreviousData,
    refetchInterval: live ? ANOMALIES_REFRESH_MS : false,
    refetchIntervalInBackground: false,
  })
}

export function useReviewAnomaly() {
  const api = useApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, verdict, comment }: { id: number; verdict: ReviewVerdict; comment: string | null }) => api.reviewAnomaly(id, verdict, comment),
    onSuccess: () => client.invalidateQueries({ queryKey: ['anomalies'] }),
  })
}

// --- Predictive maintenance (M9) ---------------------------------------------------------------------------------

/** The maintenance job runs hourly: lists refresh every 5 minutes while visible. */
const MAINTENANCE_REFRESH_MS = 5 * 60_000

export function useMaintenance(query: MaintenanceQuery) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.maintenance(query),
    queryFn: () => api.maintenance(query),
    placeholderData: keepPreviousData,
    refetchInterval: MAINTENANCE_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

export function useDeviceMaintenance(deviceId: string) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.deviceMaintenance(deviceId),
    queryFn: () => api.deviceMaintenance(deviceId),
    refetchInterval: MAINTENANCE_REFRESH_MS,
    refetchIntervalInBackground: false,
  })
}

export function useSetMaintenanceStatus() {
  const api = useApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status, comment }: { id: number; status: 'planned' | 'done'; comment: string | null }) =>
      api.setMaintenanceStatus(id, status, comment),
    onSuccess: () => client.invalidateQueries({ queryKey: ['maintenance'] }),
  })
}

// --- Site digests (M8) -------------------------------------------------------------------------------------------

/** While a regeneration request is pending, the digest is refetched at this interval until the job serves it. */
export const DIGEST_PENDING_REFRESH_MS = 20_000

export function useSiteDigest(siteId: string, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.siteDigest(siteId),
    queryFn: () => api.siteDigest(siteId),
    enabled,
    refetchInterval: (query) => (query.state.data?.pendingRequest ? DIGEST_PENDING_REFRESH_MS : false),
    refetchIntervalInBackground: false,
  })
}

export function useRequestSiteDigest(siteId: string) {
  const api = useApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.requestSiteDigest(siteId),
    onSuccess: (pendingRequest) => {
      client.setQueryData<SiteDigest>(queryKeys.siteDigest(siteId), (current) => current && { ...current, pendingRequest })
    },
  })
}
