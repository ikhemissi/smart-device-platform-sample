import { describe, expect, it } from 'vitest'
import type { BatteryForecast } from '../../api/types'
import { daysLeftText, levelText, percentText, readStatuses, reasonText, trendText } from './maintenanceText'

const battery = (changes: Partial<BatteryForecast> = {}): BatteryForecast => ({
  metric: 'battery_voltage',
  unit: 'V',
  level: 2.812,
  slope_per_day: -0.0123,
  days_left: 5.24,
  since: '2026-10-20T00:00:00Z',
  cutoff: 2.5,
  ...changes,
})

describe('maintenance text', () => {
  it('shows the battery forecast', () => {
    expect(levelText(battery())).toBe('2.81 V')
    expect(trendText(battery())).toBe('-0.012 V/day')
    expect(trendText(battery({ slope_per_day: null }))).toBeNull()
    expect(daysLeftText(battery())).toBe('5.2 days')
    expect(daysLeftText(battery({ days_left: 34.6 }))).toBe('35 days')
    expect(daysLeftText(battery({ days_left: null }))).toBe('Not discharging')
    expect(daysLeftText(battery({ days_left: null, slope_per_day: null }))).toBe('Not enough history')
    expect(daysLeftText(null)).toBe('No battery reported')
    expect(percentText(0.724)).toBe('72 %')
  })

  it('explains each rule from its figures', () => {
    expect(reasonText({ rule: 'battery_days_left', value: 5.2, threshold: 7, unit: 'days', level: 2.61, level_unit: 'V' })).toBe(
      'Battery runs out in 5.2 days (2.61 V)',
    )
    expect(reasonText({ rule: 'silent', value: 42.4, threshold: 15, unit: 'minutes' })).toBe('Silent for 42 min')
    expect(reasonText({ rule: 'reporting_gaps', value: 6.1, threshold: 5, unit: 'times the usual interval' })).toBe(
      'Longest gap 6.1 × the usual interval',
    )
    expect(reasonText({ rule: 'rejected_messages', value: 7, threshold: 5 })).toBe('7 rejected messages in 24 h')
    expect(reasonText({ rule: 'battery_forecast', value: 2.93, unit: 'V', days_left: 34.2 })).toBe('Battery 2.93 V, 34 days left')
    expect(reasonText({ rule: 'probability', value: 0.62 })).toBe('Decision model: 62 %')
  })

  it('reads the statuses of the URL, the queue by default', () => {
    expect(readStatuses(null)).toEqual(['open', 'planned'])
    expect(readStatuses('done,bogus')).toEqual(['done'])
    expect(readStatuses('bogus')).toEqual(['open', 'planned'])
  })
})
