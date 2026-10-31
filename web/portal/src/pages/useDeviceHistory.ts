import type { UseQueryResult } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useSeries } from '../api/queries'
import type { ReportedMetric, Series } from '../api/types'
import { metricLabel } from '../metricLabel'
import { type DevicePageState, defaultMetrics } from './devicePageState'

/** Points requested for a chart: 1 h → raw, 24 h → 5-minute, 7 d → 15-minute, 30 d → 1-hour buckets at 30 s reporting. */
const MAX_POINTS = 800

export interface DeviceHistory {
  /** Metrics the device reported in the retention period. */
  available: string[]
  /** Charted metrics: those of the URL, or the default ones, among the reported ones. */
  selected: string[]
  /** Label of each reported metric. */
  labels: Record<string, string>
  series: UseQueryResult<Series>
}

/** The chart's metrics and their series over the page's period. */
export function useDeviceHistory(
  deviceId: string,
  state: DevicePageState,
  presetMs: number | undefined,
  metrics: ReportedMetric[] | undefined,
): DeviceHistory {
  const available = useMemo(() => metrics?.map((m) => m.metric) ?? [], [metrics])
  const selected = (state.metrics ?? defaultMetrics(available)).filter((m) => metrics === undefined || available.includes(m))
  const labels = useMemo(
    () => Object.fromEntries((metrics ?? []).map((m) => [m.metric, metricLabel(m.metric, m.unit, m.description)])),
    [metrics],
  )
  const series = useSeries({
    deviceId,
    metrics: selected,
    period: state.period,
    presetMs,
    from: state.from,
    to: state.to,
    maxPoints: MAX_POINTS,
  })
  return { available, selected, labels, series }
}
