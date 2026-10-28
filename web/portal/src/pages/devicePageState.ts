export const PERIODS = [
  { key: '1h', label: '1 h', ms: 3_600_000 },
  { key: '24h', label: '24 h', ms: 86_400_000 },
  { key: '7d', label: '7 d', ms: 7 * 86_400_000 },
  { key: '30d', label: '30 d', ms: 30 * 86_400_000 },
] as const

export const DEFAULT_PERIOD = '24h'
export const MAX_METRICS = 4
export const RETENTION_MS = 30 * 86_400_000

export interface DevicePageState {
  metrics: string[] | undefined
  period: string
  from?: Date
  to?: Date
}

/** Chart state lives in the URL: `?metrics=temperature,humidity&period=7d`, or `period=custom&from=…&to=…`. */
export function readState(params: URLSearchParams): DevicePageState {
  const metrics = params.get('metrics')?.split(',').map((m) => m.trim()).filter(Boolean).slice(0, MAX_METRICS)
  const period = params.get('period') ?? DEFAULT_PERIOD
  if (period === 'custom') {
    const from = parseDate(params.get('from'))
    const to = parseDate(params.get('to'))
    if (from && to && from < to) {
      return { metrics, period, from, to }
    }
    return { metrics, period: DEFAULT_PERIOD }
  }
  return { metrics: metrics?.length ? metrics : undefined, period: PERIODS.some((p) => p.key === period) ? period : DEFAULT_PERIOD }
}

function parseDate(value: string | null): Date | undefined {
  if (!value) {
    return undefined
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date
}

/** Temperature when reported, otherwise the first metric. */
export function defaultMetrics(available: string[]): string[] {
  if (available.includes('temperature')) {
    return ['temperature']
  }
  return available.length > 0 ? [available[0]] : []
}
