import type { DigestFigures } from '../../api/types'

/** Device ids that appear in the figures (silent devices, alerts, anomalies), longest first. */
export function figureDeviceIds(figures: DigestFigures): string[] {
  const ids = new Set<string>()
  for (const list of [figures.devices?.silent.items, figures.alerts?.items, figures.anomalies?.items]) {
    for (const item of list ?? []) {
      if (item.deviceId) ids.add(item.deviceId)
    }
  }
  return [...ids].sort((a, b) => b.length - a.length || a.localeCompare(b))
}

export type BriefPart = { text: string } | { deviceId: string }

const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Splits the brief around the given device ids (whole ids only: `paris-01-ruuvi-001` is not found in `paris-01-ruuvi-0010`). */
export function briefParts(brief: string, deviceIds: string[]): BriefPart[] {
  if (deviceIds.length === 0) return [{ text: brief }]
  const pattern = new RegExp(`(?<![\\w-])(${deviceIds.map(escape).join('|')})(?![\\w-])`, 'g')
  const parts: BriefPart[] = []
  let last = 0
  for (const match of brief.matchAll(pattern)) {
    if (match.index > last) parts.push({ text: brief.slice(last, match.index) })
    parts.push({ deviceId: match[0] })
    last = match.index + match[0].length
  }
  if (last < brief.length) parts.push({ text: brief.slice(last) })
  return parts
}

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })
const signed = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2, signDisplay: 'exceptZero' })

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '–' : number.format(value)
}

/** "+0.2", "−1.5", "0"; "–" when absent. */
export function formatChange(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '–' : `${signed.format(value)}${suffix}`
}

/** Whole days between two instants ("Previous 7 days"). */
export function days(from: string, to: string): number {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000)
}

/** "Real event: 6, Sensor fault: 8" (keys without a label as they are). */
export function countsText(counts: Record<string, number | undefined>, labels: Record<string, string> = {}): string {
  return Object.entries(counts)
    .filter((entry): entry is [string, number] => (entry[1] ?? 0) > 0)
    .map(([key, count]) => `${labels[key] ?? key}: ${number.format(count)}`)
    .join(', ')
}
