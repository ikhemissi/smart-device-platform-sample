import type { Series } from '../api/types'

/** Human label of a metric key: description when known, e.g. "Air temperature (°C)". */
export function metricLabel(metric: string, unit: string | null | undefined, description?: string | null): string {
  const base = description || metric.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())
  return unit ? `${base} (${unit})` : base
}

/** Identifies the data drawn by a chart. */
export function seriesKey(series: Series): string {
  return `${series.resolution}|${series.from}|${series.to}|${series.series.map((s) => `${s.metric}:${s.points.length}`).join(',')}`
}

const BUCKET_NAMES: Record<string, string> = {
  '1m': '1-minute',
  '5m': '5-minute',
  '15m': '15-minute',
  '1h': '1-hour',
  '6h': '6-hour',
  '1d': '1-day',
}

export function bucketName(resolution: string): string | undefined {
  return BUCKET_NAMES[resolution]
}

const format = (value: number) => (Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(2))
const time = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

type Labels = Record<string, string>

/**
 * ECharts option for a device series (M3 design D5): one value axis per unit; raw values as lines; aggregated buckets as
 * an average line over a shaded min–max band (stacked: `min`, then `max - min`).
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- ECharts options are loosely typed
export function buildChartOption(series: Series, labels: Labels): Record<string, any> {
  const aggregated = series.resolution !== 'raw'
  const units = [...new Set(series.series.map((s) => s.unit ?? ''))]
  const yAxis = units.map((unit, i) => ({
    type: 'value',
    name: unit,
    position: i % 2 === 0 ? 'left' : 'right',
    offset: Math.floor(i / 2) * 56,
    scale: true,
    axisLine: { show: true },
    splitLine: { show: i === 0 },
  }))
  const out: unknown[] = []
  series.series.forEach((s, index) => {
    const yAxisIndex = units.indexOf(s.unit ?? '')
    const name = labels[s.metric] ?? s.metric
    if (!aggregated) {
      out.push({
        id: s.metric,
        name,
        type: 'line',
        yAxisIndex,
        showSymbol: false,
        sampling: 'lttb',
        data: s.points.map((p) => [Date.parse(p.t), p.value]),
      })
      return
    }
    const stack = `band-${s.metric}`
    out.push(
      {
        id: `${s.metric}-min`,
        type: 'line',
        yAxisIndex,
        stack,
        stackStrategy: 'all',
        symbol: 'none',
        silent: true,
        lineStyle: { opacity: 0 },
        tooltip: { show: false },
        data: s.points.map((p) => [Date.parse(p.t), p.min]),
      },
      {
        id: `${s.metric}-band`,
        type: 'line',
        yAxisIndex,
        stack,
        stackStrategy: 'all',
        symbol: 'none',
        silent: true,
        lineStyle: { opacity: 0 },
        areaStyle: { opacity: 0.18, color: PALETTE[index % PALETTE.length] },
        tooltip: { show: false },
        data: s.points.map((p) => [Date.parse(p.t), (p.max ?? 0) - (p.min ?? 0)]),
      },
      {
        id: s.metric,
        name,
        type: 'line',
        yAxisIndex,
        showSymbol: false,
        color: PALETTE[index % PALETTE.length],
        data: s.points.map((p) => [Date.parse(p.t), p.avg, p.min, p.max, p.count]),
      },
    )
  })

  return {
    animation: false,
    color: PALETTE,
    aria: { enabled: true },
    grid: { left: 24 + Math.ceil(units.length / 2) * 56, right: 24 + Math.floor(units.length / 2) * 56, top: 48, bottom: 64, containLabel: false },
    legend: { top: 0, data: series.series.map((s) => labels[s.metric] ?? s.metric) },
    xAxis: { type: 'time' },
    yAxis,
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20, bottom: 8 }],
    tooltip: {
      trigger: 'axis',
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- ECharts callback parameters
      formatter: (params: any[]) => tooltip(params, series, labels),
    },
    series: out,
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- ECharts callback parameters
function tooltip(params: any[], series: Series, labels: Labels): string {
  const visible = params.filter((p) => !String(p.seriesId).endsWith('-min') && !String(p.seriesId).endsWith('-band'))
  if (visible.length === 0) {
    return ''
  }
  const [t] = visible[0].value as number[]
  const bucket = bucketName(series.resolution)
  const header = bucket ? `${time.format(new Date(t))} · ${bucket} average` : time.format(new Date(t))
  const lines = visible.map((p) => {
    const metric = series.series.find((s) => s.metric === p.seriesId)
    const unit = metric?.unit ? ` ${metric.unit}` : ''
    const [, avg, min, max] = p.value as number[]
    const label = escapeHtml(labels[p.seriesId] ?? p.seriesId)
    return bucket
      ? `${p.marker}${label}: ${format(avg)}${unit} <span style="opacity:.75">(min ${format(min)}, max ${format(max)})</span>`
      : `${p.marker}${label}: ${format(avg)}${unit}`
  })
  return [escapeHtml(header), ...lines].join('<br/>')
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}

/** Fluent-inspired palette with good contrast on white. */
export const PALETTE = ['#0f6cbd', '#c50f1f', '#107c10', '#8764b8']
