import type { Anomaly, AnomalyClassification, AnomalyStatus, ReviewVerdict } from '../../api/types'

export const CLASSIFICATION_LABELS: Record<AnomalyClassification, string> = {
  real_event: 'Real event',
  sensor_fault: 'Sensor fault',
  expected_pattern: 'Expected pattern',
  cannot_tell: 'Cannot tell',
}

export const STATUS_LABELS: Record<AnomalyStatus, string> = {
  confirmed: 'Confirmed',
  to_review: 'To review',
  reviewed: 'Reviewed',
  dismissed: 'Dismissed',
  pending: 'Pending',
}

/** Statuses in the order they are offered in filters. */
export const STATUSES = Object.keys(STATUS_LABELS) as AnomalyStatus[]

/** The anomalies to act on (the API's default for the list). */
export const DEFAULT_STATUSES: AnomalyStatus[] = ['confirmed', 'to_review']

/** The device page's default (the API's default for a device), and with dismissed ones when requested. */
export const DEVICE_STATUSES: AnomalyStatus[] = ['confirmed', 'to_review', 'reviewed']
export const DEVICE_STATUSES_WITH_DISMISSED: AnomalyStatus[] = [...DEVICE_STATUSES, 'dismissed']

export const KIND_LABELS: Record<Anomaly['kind'], string> = { deviation: 'Deviation', flat: 'Flat readings', jump: 'Jump' }

export const VERDICT_LABELS: Record<ReviewVerdict, string> = { correct: 'Classification correct', incorrect: 'Classification incorrect' }

/** Device page window around an anomaly: from an hour before it starts to an hour after it ends (not in the future). */
export const LINK_MARGIN_MS = 3_600_000

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2, useGrouping: false })

/** "Real event · 91 %"; null while the anomaly is not classified. */
export function classificationText(anomaly: Pick<Anomaly, 'classification' | 'confidence'>): string | null {
  if (!anomaly.classification) return null
  const label = CLASSIFICATION_LABELS[anomaly.classification]
  return anomaly.confidence === null ? label : `${label} · ${Math.round(anomaly.confidence * 100)} %`
}

export function amount(value: number, unit: string | null): string {
  return unit ? `${number.format(value)} ${unit}` : number.format(value)
}

/** "28.4–31.2 °C, last 30.9 °C" (a single value when the readings did not change). */
export function observedText(anomaly: Pick<Anomaly, 'observedMin' | 'observedMax' | 'observedLast' | 'unit'>): string {
  const { observedMin: min, observedMax: max, observedLast: last, unit } = anomaly
  const range = min === max ? amount(min, unit) : `${number.format(min)}–${amount(max, unit)}`
  return `${range}, last ${amount(last, unit)}`
}

export function usualText(anomaly: Pick<Anomaly, 'usualValue' | 'unit'>): string {
  return `usual ${amount(anomaly.usualValue, anomaly.unit)}`
}

/** "25 min", "3 h 10 min", "2 d". */
export function durationText(startedAt: string, endedAt: string): string {
  const minutes = Math.round((Date.parse(endedAt) - Date.parse(startedAt)) / 60_000)
  if (minutes < 1) return 'under a minute'
  if (minutes < 60) return `${minutes} min`
  if (minutes < 48 * 60) return minutes % 60 === 0 ? `${minutes / 60} h` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`
  return `${Math.round(minutes / 1440)} d`
}

/** The device page over the anomaly's period, charting its metric. */
export function deviceLink(anomaly: Pick<Anomaly, 'deviceId' | 'metric' | 'startedAt' | 'endedAt'>, now: number = Date.now()): string {
  const from = Date.parse(anomaly.startedAt) - LINK_MARGIN_MS
  const to = Math.max(Math.min(Date.parse(anomaly.endedAt) + LINK_MARGIN_MS, now), from + 60_000)
  const params = new URLSearchParams({
    metrics: anomaly.metric,
    period: 'custom',
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
  })
  return `/devices/${encodeURIComponent(anomaly.deviceId)}?${params}`
}

/** Statuses of the `status` URL parameter (comma-separated); the default ones when absent or invalid. */
export function readStatuses(value: string | null): AnomalyStatus[] {
  const statuses = STATUSES.filter((s) => (value ?? '').split(',').includes(s))
  return statuses.length > 0 ? statuses : DEFAULT_STATUSES
}
