import type { Alert, AlertNotification } from '../../api/types'

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
