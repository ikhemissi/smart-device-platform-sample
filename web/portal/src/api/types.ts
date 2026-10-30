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

/** AI assistant (M6). A citation names a device (with the metrics and period used) or a site. */
export interface Citation {
  kind: 'device' | 'site'
  deviceId?: string
  siteId?: string
  metrics?: string[]
  from?: string
  to?: string
}

export interface AssistantConversation {
  id: string
  createdAt: string
}

/** Data the assistant consulted while answering, with its tool arguments. */
export interface ToolActivity {
  name: string
  arguments: Record<string, unknown>
}

export interface AnswerHandlers {
  onTool(tool: ToolActivity): void
  onDelta(text: string): void
}

/** Anomaly detection (M7). `pending`: not classified yet; `reviewed`: an Editor gave a verdict. */
export type AnomalyStatus = 'pending' | 'confirmed' | 'to_review' | 'dismissed' | 'reviewed'
export type AnomalyClassification = 'real_event' | 'sensor_fault' | 'expected_pattern' | 'cannot_tell'
export type ReviewVerdict = 'correct' | 'incorrect'

export interface AnomalyReview {
  reviewedAt: string
  /** Display name of the Editor. */
  reviewedBy: string
  verdict: ReviewVerdict
  comment: string | null
}

export interface Anomaly {
  id: number
  deviceId: string
  deviceName: string | null
  siteId: string
  metric: string
  unit: string | null
  kind: 'deviation' | 'flat' | 'jump'
  startedAt: string
  endedAt: string
  observedMin: number
  observedMax: number
  observedMean: number
  observedLast: number
  /** The device's usual value at that time of day. */
  usualValue: number
  spread: number
  score: number
  status: AnomalyStatus
  decision: Exclude<AnomalyStatus, 'reviewed'>
  classification: AnomalyClassification | null
  /** Probability of the classification (0–1). */
  confidence: number | null
  probabilities: Partial<Record<AnomalyClassification, number>>
  confidenceThreshold: number | null
  decisionModel: string | null
  decisionModelVersion: string | null
  explanation: string | null
  explanationModel: string | null
  detectedAt: string
  updatedAt: string
  review: AnomalyReview | null
}

export interface AnomalyQuery {
  siteId?: string
  deviceId?: string
  status?: AnomalyStatus[]
  from?: string
  to?: string
  limit?: number
}

// --- Predictive maintenance (M9) ---------------------------------------------------------------------------------

/** `open` and `planned` items are in the queue; `done` (by an Editor) and `resolved` (by the platform) close them. */
export type MaintenanceStatus = 'open' | 'planned' | 'done' | 'resolved'

/** Battery forecast computed by the maintenance job (snake_case, as stored). */
export interface BatteryForecast {
  metric: 'battery_voltage' | 'battery_level'
  unit: string
  level: number
  /** Trend per day (negative while discharging); null with less than 24 hours of history. */
  slope_per_day: number | null
  /** Days until the cut-off; null when not discharging or without enough history. */
  days_left: number | null
  since: string
  cutoff: number
}

/** A computed figure that crossed a rule, or (when none did) the battery forecast and the probability. */
export interface MaintenanceReason {
  rule: 'battery_days_left' | 'silent' | 'reporting_gaps' | 'rejected_messages' | 'battery_forecast' | 'probability'
  value: number
  threshold?: number
  unit?: string
  [detail: string]: unknown
}

export interface MaintenanceItem {
  id: number
  deviceId: string
  deviceName: string | null
  siteId: string
  status: MaintenanceStatus
  /** Probability of an intervention within 7 days. */
  probability: number
  reasons: MaintenanceReason[]
  battery: BatteryForecast | null
  workOrder: string | null
  workOrderModel: string | null
  workOrderAt: string | null
  openedAt: string
  updatedAt: string
  resolvedAt: string | null
  statusBy: string | null
  statusAt: string | null
  statusComment: string | null
}

export interface MaintenanceAssessment {
  assessedAt: string
  battery: BatteryForecast | null
  signals: Record<string, unknown>
  probability: number
  decisionModel: string
  decisionModelVersion: string
}

export interface DeviceMaintenance {
  deviceId: string
  /** Whether the device is assessed (maintenance on and its site configured). */
  enabled: boolean
  assessment: MaintenanceAssessment | null
  item: MaintenanceItem | null
}

export interface MaintenanceQuery {
  siteId?: string
  status?: MaintenanceStatus[]
}

// --- Site digests (M8) -------------------------------------------------------------------------------------------

/** A list of the figures: its size, and at most 20 items. */
export interface BoundedList<T> {
  total: number
  items: T[]
}

/** The figures of a digest, computed by the platform (the brief is written from them only). */
export interface DigestFigures {
  site?: { id: string; name: string | null }
  period?: { from: string; to: string }
  comparison?: { from: string; to: string }
  devices?: {
    total: number
    reporting: number
    silent: BoundedList<{ deviceId: string; name: string | null; lastSeenAt: string | null }>
  }
  metrics?: {
    metric: string
    unit: string
    devices: number
    average: number
    min: number
    max: number
    /** Absent when the comparison period has no data. */
    comparisonAverage?: number
    change?: number
    changePercent?: number
  }[]
  alerts?: {
    openNow: number
  } & BoundedList<{
    deviceId: string
    metric: string
    direction: 'above' | 'below'
    status: 'open' | 'closed'
    openedAt: string
    closedAt: string | null
    worstValue: number
  }>
  anomalies?: {
    byStatus: Partial<Record<AnomalyStatus, number>>
    byClassification: Partial<Record<AnomalyClassification, number>>
  } & BoundedList<{
    deviceId: string
    metric: string
    kind: Anomaly['kind']
    status: AnomalyStatus
    classification: AnomalyClassification | null
    confidencePercent: number | null
    startedAt: string
    endedAt: string
  }>
  rejectedMessages?: { total: number; byReason: Record<string, number> }
  certificates?: { expiringWithin30Days: { slot: 'primary' | 'secondary'; subject: string; notAfter: string; daysLeft: number }[] }
}

export interface Digest {
  id: number
  trigger: 'scheduled' | 'requested'
  periodFrom: string
  periodTo: string
  /** Start of the comparison period (it ends at periodFrom). */
  comparisonFrom: string
  generatedAt: string
  /** Null when the language model failed; a later run adds it. */
  brief: string | null
  briefModel: string | null
  /** Display name of the Editor who requested it. */
  requestedBy: string | null
  figures: DigestFigures
}

/** A regeneration request not served yet. */
export interface DigestRequest {
  requestedAt: string
  /** Display name of the Editor. */
  requestedBy: string
}

export interface SiteDigest {
  siteId: string
  /** Whether the site gets digests in this environment. */
  enabled: boolean
  /** The latest digest; null when there is none yet. */
  digest: Digest | null
  pendingRequest: DigestRequest | null
}
