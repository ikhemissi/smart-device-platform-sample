import type { ReportedMetric } from './api/types'

/** Human label of a metric key: description when known, e.g. "Air temperature (°C)". */
export function metricLabel(metric: string, unit: string | null | undefined, description?: string | null): string {
  const base = description || metric.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())
  return unit ? `${base} (${unit})` : base
}

/** Labels metrics with the description and unit the device reported; `unit`, when given, overrides the reported one. */
export function metricLabeler(metrics: ReportedMetric[]) {
  return (metric: string, unit?: string | null): string => {
    const reported = metrics.find((m) => m.metric === metric)
    return metricLabel(metric, unit ?? reported?.unit, reported?.description)
  }
}
