import type { Alert, AlertNotification, Threshold, ThresholdInput } from '../../api/types'

export const MAX_RECIPIENTS = 10
const EMAIL = /^[^@\s,;<>"]+@[^@\s,;<>"]+\.[^@\s,;<>"]+$/

export interface ThresholdFormValues {
  metric: string
  min: string
  max: string
  recipients: string
  enabled: boolean
}

export type ThresholdFormErrors = Partial<Record<'metric' | 'min' | 'max' | 'recipients', string>>

export function initialValues(threshold?: Threshold, metric = ''): ThresholdFormValues {
  return threshold
    ? {
        metric: threshold.metric,
        min: threshold.min?.toString() ?? '',
        max: threshold.max?.toString() ?? '',
        recipients: threshold.recipients.join('\n'),
        enabled: threshold.enabled,
      }
    : { metric, min: '', max: '', recipients: '', enabled: true }
}

/** Addresses separated by commas, semicolons, spaces or new lines; duplicates (case-insensitive) removed. */
export function parseRecipients(text: string): string[] {
  const seen = new Set<string>()
  return text
    .split(/[\s,;]+/)
    .map((r) => r.trim())
    .filter((r) => r && !seen.has(r.toLowerCase()) && seen.add(r.toLowerCase()))
}

function parseNumber(text: string): number | null | undefined {
  const trimmed = text.trim().replace(',', '.')
  if (!trimmed) return null
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : undefined
}

/** Same rules as the API (which stays authoritative): returns either the input to send or the errors per field. */
export function validate(values: ThresholdFormValues): { input?: ThresholdInput; errors: ThresholdFormErrors } {
  const errors: ThresholdFormErrors = {}
  const min = parseNumber(values.min)
  const max = parseNumber(values.max)
  const recipients = parseRecipients(values.recipients)
  if (!values.metric) errors.metric = 'Choose a metric.'
  if (min === undefined) errors.min = 'Enter a number.'
  if (max === undefined) errors.max = 'Enter a number.'
  if (min === null && max === null) errors.min = 'Set a minimum, a maximum or both.'
  if (typeof min === 'number' && typeof max === 'number' && min >= max) errors.min = 'The minimum must be lower than the maximum.'
  if (recipients.length === 0) errors.recipients = 'Enter at least one email address.'
  else if (recipients.length > MAX_RECIPIENTS) errors.recipients = `Enter at most ${MAX_RECIPIENTS} email addresses.`
  else {
    const invalid = recipients.filter((r) => r.length > 254 || !EMAIL.test(r))
    if (invalid.length > 0) errors.recipients = `Not a valid email address: ${invalid.join(', ')}`
  }
  if (Object.keys(errors).length > 0) return { errors }
  return { input: { metric: values.metric, min: min ?? null, max: max ?? null, recipients, enabled: values.enabled }, errors }
}

export function quantity(value: number | null | undefined, unit: string | null | undefined): string {
  if (value === null || value === undefined) return '–'
  return unit ? `${value} ${unit}` : String(value)
}

/** "between 2 °C and 8 °C", "at most 8 °C" or "at least 2 °C". */
export function rangeText(min: number | null, max: number | null, unit: string | null): string {
  if (min !== null && max !== null) return `between ${quantity(min, unit)} and ${quantity(max, unit)}`
  if (max !== null) return `at most ${quantity(max, unit)}`
  return `at least ${quantity(min, unit)}`
}

/** "Alert email sent", "Reminder sent", "Email failed"… for the alert history. */
export function notificationSummary(notifications: AlertNotification[]): { text: string; failed: boolean } {
  if (notifications.length === 0) return { text: 'No email', failed: false }
  const failed = notifications.some((n) => n.status === 'FAILED')
  const label = { ALERT: 'alert', REMINDER: 'reminder', RESOLVED: 'back to normal' } as const
  const parts = notifications.map((n) => `${label[n.kind]} ${n.status === 'SENT' ? 'sent' : n.status === 'FAILED' ? 'failed' : 'pending'}`)
  return { text: parts.join(', ').replace(/^./, (c) => c.toUpperCase()), failed }
}

export function alertSummary(alert: Alert): string {
  const limit = alert.direction === 'ABOVE' ? `above ${quantity(alert.thresholdMax, alert.unit)}` : `below ${quantity(alert.thresholdMin, alert.unit)}`
  return `${limit}, worst ${quantity(alert.worstValue, alert.unit)}`
}
