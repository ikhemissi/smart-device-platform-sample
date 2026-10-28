import { describe, expect, it } from 'vitest'
import type { Series } from '../api/types'
import { buildChartOption, metricLabel } from './chartOption'

const labels = { temperature: 'Air temperature (°C)', humidity: 'Relative humidity (%)' }

const raw: Series = {
  deviceId: 'd',
  from: '2026-10-28T09:00:00Z',
  to: '2026-10-28T10:00:00Z',
  resolution: 'raw',
  series: [
    { metric: 'temperature', unit: '°C', points: [{ t: '2026-10-28T09:00:00Z', value: 21.5 }, { t: '2026-10-28T09:00:30Z', value: 21.6 }] },
    { metric: 'humidity', unit: '%', points: [{ t: '2026-10-28T09:00:00Z', value: 45 }] },
  ],
}

const aggregated: Series = {
  deviceId: 'd',
  from: '2026-09-28T00:00:00Z',
  to: '2026-10-28T00:00:00Z',
  resolution: '6h',
  bucketSeconds: 21600,
  series: [{ metric: 'temperature', unit: '°C', points: [{ t: '2026-10-27T06:00:00Z', min: -2, avg: 1, max: 3, count: 720 }] }],
}

describe('chart option', () => {
  it('draws raw values as lines with one value axis per unit', () => {
    const option = buildChartOption(raw, labels)

    expect(option.yAxis.map((a: { name: string; position: string }) => [a.name, a.position])).toEqual([
      ['°C', 'left'],
      ['%', 'right'],
    ])
    expect(option.series).toHaveLength(2)
    expect(option.series[1]).toMatchObject({ name: 'Relative humidity (%)', type: 'line', yAxisIndex: 1 })
    expect(option.series[0].data).toEqual([
      [Date.parse('2026-10-28T09:00:00Z'), 21.5],
      [Date.parse('2026-10-28T09:00:30Z'), 21.6],
    ])
    expect(option.legend.data).toEqual(['Air temperature (°C)', 'Relative humidity (%)'])
  })

  it('draws buckets as an average line over a min–max band, also below zero', () => {
    const option = buildChartOption(aggregated, labels)
    const [lower, band, average] = option.series
    const t = Date.parse('2026-10-27T06:00:00Z')

    expect(lower).toMatchObject({ stack: 'band-temperature', stackStrategy: 'all', data: [[t, -2]], tooltip: { show: false } })
    expect(band).toMatchObject({ stack: 'band-temperature', stackStrategy: 'all', data: [[t, 5]] })
    expect(band.areaStyle.opacity).toBeGreaterThan(0)
    expect(average).toMatchObject({ name: 'Air temperature (°C)', data: [[t, 1, -2, 3, 720]] })
  })

  it('states min, avg, max with units and the bucket size in the tooltip', () => {
    const option = buildChartOption(aggregated, labels)
    const t = Date.parse('2026-10-27T06:00:00Z')

    const html: string = option.tooltip.formatter([
      { seriesId: 'temperature-min', value: [t, -2], marker: '' },
      { seriesId: 'temperature', value: [t, 1, -2, 3, 720], marker: '●' },
    ])

    expect(html).toContain('6-hour average')
    expect(html).toContain('Air temperature (°C): 1.00 °C')
    expect(html).toContain('min -2.00, max 3.00')
  })

  it('labels metrics with description and unit', () => {
    expect(metricLabel('temperature', '°C', 'Air temperature')).toBe('Air temperature (°C)')
    expect(metricLabel('movement_counter', '', null)).toBe('Movement counter')
  })
})
