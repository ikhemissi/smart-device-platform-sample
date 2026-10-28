export interface CurrentUser {
  objectId: string
  displayName: string
  userPrincipalName: string
  canRead: boolean
  canWrite: boolean
}

export type DeviceStatus = 'reporting' | 'silent'

export interface DeviceSummary {
  deviceId: string
  displayName: string | null
  siteId: string
  lastPayloadFormat: string
  firstSeenAt: string
  lastSeenAt: string
  status: DeviceStatus
}

export interface Location {
  latitude: number
  longitude: number
  label: string | null
}

export interface DeviceDetails extends DeviceSummary {
  description: string | null
  location: Location | null
  updatedAt: string | null
  updatedBy: { objectId: string; displayName: string } | null
}

export interface DevicePage {
  items: DeviceSummary[]
  page: number
  pageSize: number
  total: number
}

export interface Site {
  siteId: string
  displayName: string
  deviceCount: number
}

export type SortField = 'displayName' | 'siteId' | 'firstSeen' | 'lastSeen'
export type SortOrder = 'asc' | 'desc'

export interface DeviceQuery {
  siteId?: string
  status?: DeviceStatus
  search?: string
  sort: SortField
  order: SortOrder
  page: number
  pageSize: number
}

/** Fields an Editor can change (JSON Merge Patch: null clears a field). */
export interface DeviceEdit {
  displayName: string | null
  description: string | null
  location: Location | null
}

/** RFC 9457 problem details returned by the API and the gateway. */
export interface Problem {
  title?: string
  status?: number
  detail?: string
  errors?: Record<string, string[]>
}

export interface Measurement {
  measuredAt: string
  payloadFormat: string
  metrics: Record<string, number>
  attributes: Record<string, string>
}

export interface ReportedMetric {
  metric: string
  unit: string | null
  description: string | null
  lastSeenAt: string
}

/** A raw value (`value`) or an aggregated bucket (`min`, `avg`, `max`, `count`). */
export interface SeriesPoint {
  t: string
  value?: number
  min?: number
  avg?: number
  max?: number
  count?: number
}

export interface Series {
  deviceId: string
  from: string
  to: string
  /** `raw`, or the bucket size: `1m`, `5m`, `15m`, `1h`, `6h`, `1d`. */
  resolution: string
  bucketSeconds?: number
  series: { metric: string; unit: string | null; points: SeriesPoint[] }[]
}
