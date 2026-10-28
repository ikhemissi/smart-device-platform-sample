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

export type CertificateStatus = 'valid' | 'expiring' | 'expired'

export interface SiteCertificate {
  siteId: string
  /** SHA-256, lowercase hex. */
  fingerprint: string
  slot: 'primary' | 'secondary'
  subject: string
  issuer: string
  notBefore: string
  notAfter: string
  status: CertificateStatus
  /** Object id of the Editor, or `deployment` / `reconciliation`. */
  addedBy: string
  addedByName: string | null
  addedAt: string
}

export interface DeviceOperation {
  id: string
  siteId: string
  kind: 'DISABLE_DEVICES' | 'ENABLE_DEVICES'
  status: 'PENDING' | 'DONE' | 'FAILED'
  deviceCount: number
}

export interface CertificateChange {
  certificate: SiteCertificate
  deviceOperation: DeviceOperation | null
}

export interface AuditEvent {
  id: number
  siteId: string
  occurredAt: string
  actorId: string
  actorName: string | null
  action: string
  fingerprint: string | null
  details: Record<string, unknown>
}

/** Expected range of a metric of a device (M5). `etag` goes in If-Match when changing it. */
export interface Threshold {
  id: string
  deviceId: string
  metric: string
  unit: string | null
  min: number | null
  max: number | null
  recipients: string[]
  enabled: boolean
  etag: string
  updatedAt: string
  updatedByName: string | null
  openAlertId: number | null
}

export interface ThresholdInput {
  metric: string
  min: number | null
  max: number | null
  recipients: string[]
  enabled: boolean
}

export type AlertStatus = 'OPEN' | 'CLOSED' | 'CANCELLED'

export interface AlertNotification {
  kind: 'ALERT' | 'REMINDER' | 'RESOLVED'
  status: 'PENDING' | 'SENT' | 'FAILED'
  recipients: number
  attempts: number
  createdAt: string
  sentAt: string | null
  lastError: string | null
}

export interface Alert {
  id: number
  thresholdId: string | null
  deviceId: string
  deviceName: string | null
  siteId: string
  metric: string
  unit: string | null
  thresholdMin: number | null
  thresholdMax: number | null
  direction: 'ABOVE' | 'BELOW'
  status: AlertStatus
  openedAt: string
  closedAt: string | null
  firstValue: number
  worstValue: number
  lastValue: number
  lastBreachAt: string
  notifications: AlertNotification[]
}
