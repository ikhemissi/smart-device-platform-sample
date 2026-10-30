import type { BatteryForecast, MaintenanceReason, MaintenanceStatus } from '../../api/types'

export const STATUS_LABELS: Record<MaintenanceStatus, string> = {
  open: 'Open',
  planned: 'Planned',
  done: 'Done',
  resolved: 'Resolved',
}

/** Statuses in the order they are offered in filters. */
export const STATUSES = Object.keys(STATUS_LABELS) as MaintenanceStatus[]

/** The queue (the API's default). */
export const DEFAULT_STATUSES: MaintenanceStatus[] = ['open', 'planned']

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2, useGrouping: false })
const trend = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 2, signDisplay: 'exceptZero', useGrouping: false })

/** Statuses of the `status` URL parameter (comma-separated); the default ones when absent or invalid. */
export function readStatuses(value: string | null): MaintenanceStatus[] {
  const statuses = STATUSES.filter((s) => (value ?? '').split(',').includes(s))
  return statuses.length > 0 ? statuses : DEFAULT_STATUSES
}

/** "72 %". */
export function percentText(probability: number): string {
  return `${Math.round(probability * 100)} %`
}

function days(value: number): string {
  const rounded = value < 10 ? Math.round(value * 10) / 10 : Math.round(value)
  return `${number.format(rounded)} ${rounded === 1 ? 'day' : 'days'}`
}

/** "2.81 V", "34 %". */
export function levelText(battery: Pick<BatteryForecast, 'level' | 'unit'>): string {
  return `${number.format(battery.level)} ${battery.unit}`
}

/** "−0.012 V/day"; null without a trend (less than 24 hours of readings). */
export function trendText(battery: Pick<BatteryForecast, 'slope_per_day' | 'unit'>): string | null {
  return battery.slope_per_day === null ? null : `${trend.format(battery.slope_per_day)} ${battery.unit}/day`
}

/** "5.2 days" before the cut-off, or why there is no forecast. */
export function daysLeftText(battery: BatteryForecast | null): string {
  if (!battery) return 'No battery reported'
  if (battery.days_left !== null) return days(battery.days_left)
  return battery.slope_per_day === null ? 'Not enough history' : 'Not discharging'
}

/** One line per reason, from the figures the platform computed. */
export function reasonText(reason: MaintenanceReason): string {
  const value = reason.value
  switch (reason.rule) {
    case 'battery_days_left': {
      const level = typeof reason.level === 'number' ? ` (${number.format(reason.level)} ${String(reason.level_unit ?? '')})` : ''
      return `Battery runs out in ${days(value)}${level}`
    }
    case 'silent':
      return `Silent for ${Math.round(value)} min`
    case 'reporting_gaps':
      return `Longest gap ${number.format(value)} × the usual interval`
    case 'rejected_messages':
      return `${value} rejected ${value === 1 ? 'message' : 'messages'} in 24 h`
    case 'battery_forecast': {
      const left = typeof reason.days_left === 'number' ? `, ${days(reason.days_left)} left` : ''
      return `Battery ${number.format(value)} ${reason.unit ?? ''}${left}`
    }
    case 'probability':
      return `Decision model: ${percentText(value)}`
    default:
      return `${String(reason.rule)}: ${number.format(value)}${reason.unit ? ` ${reason.unit}` : ''}`
  }
}
