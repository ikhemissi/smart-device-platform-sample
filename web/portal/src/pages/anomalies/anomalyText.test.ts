import { describe, expect, it } from 'vitest'
import { classificationText, deviceLink, durationText, observedText, readStatuses } from './anomalyText'

describe('anomaly text', () => {
  it('shows the classification with its confidence', () => {
    expect(classificationText({ classification: 'real_event', confidence: 0.905 })).toBe('Real event · 91 %')
    expect(classificationText({ classification: 'sensor_fault', confidence: null })).toBe('Sensor fault')
    expect(classificationText({ classification: null, confidence: null })).toBeNull()
  })

  it('shows the observed range, or a single value for flat readings', () => {
    expect(observedText({ observedMin: 28.4, observedMax: 31.234, observedLast: 30.9, unit: '°C' })).toBe('28.4–31.23 °C, last 30.9 °C')
    expect(observedText({ observedMin: 1013, observedMax: 1013, observedLast: 1013, unit: null })).toBe('1013, last 1013')
  })

  it('shows durations', () => {
    const at = (minutes: number) => new Date(Date.UTC(2026, 9, 28, 10) + minutes * 60_000).toISOString()
    expect(durationText(at(0), at(0))).toBe('under a minute')
    expect(durationText(at(0), at(25))).toBe('25 min')
    expect(durationText(at(0), at(120))).toBe('2 h')
    expect(durationText(at(0), at(190))).toBe('3 h 10 min')
    expect(durationText(at(0), at(3 * 1440))).toBe('3 d')
  })

  it('links to the device page an hour around the anomaly, never in the future', () => {
    const anomaly = { deviceId: 'paris-01 #1', metric: 'temperature', startedAt: '2026-10-28T10:00:00.000Z', endedAt: '2026-10-28T10:25:00.000Z' }
    const link = new URL(deviceLink(anomaly, Date.parse('2026-10-29T00:00:00Z')), 'http://localhost')
    expect(link.pathname).toBe('/devices/paris-01%20%231')
    expect(Object.fromEntries(link.searchParams)).toEqual({
      metrics: 'temperature',
      period: 'custom',
      from: '2026-10-28T09:00:00.000Z',
      to: '2026-10-28T11:25:00.000Z',
    })
    expect(new URL(deviceLink(anomaly, Date.parse('2026-10-28T10:30:00Z')), 'http://localhost').searchParams.get('to')).toBe('2026-10-28T10:30:00.000Z')
  })

  it('reads statuses from the URL, confirmed and to review by default', () => {
    expect(readStatuses(null)).toEqual(['confirmed', 'to_review'])
    expect(readStatuses('reviewed,unknown,confirmed')).toEqual(['confirmed', 'reviewed'])
    expect(readStatuses('unknown')).toEqual(['confirmed', 'to_review'])
  })
})
