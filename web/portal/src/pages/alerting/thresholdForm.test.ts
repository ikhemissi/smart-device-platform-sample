import { describe, expect, it } from 'vitest'
import { notificationSummary, parseRecipients, rangeText, validate } from './thresholdForm'

const valid = { metric: 'temperature', min: '2', max: '8', recipients: 'ops@example.com', enabled: true }

describe('threshold form', () => {
  it('splits recipients on commas, semicolons, spaces and new lines, without duplicates', () => {
    expect(parseRecipients('ops@example.com, Cold@example.com;\nops@EXAMPLE.com  x@y.fr')).toEqual(['ops@example.com', 'Cold@example.com', 'x@y.fr'])
  })

  it('accepts a range, a maximum only or a minimum only, with a decimal comma', () => {
    expect(validate(valid).input).toEqual({ metric: 'temperature', min: 2, max: 8, recipients: ['ops@example.com'], enabled: true })
    expect(validate({ ...valid, min: '' }).input?.min).toBeNull()
    expect(validate({ ...valid, max: '', min: '2,5' }).input?.min).toBe(2.5)
  })

  it('applies the API rules', () => {
    expect(validate({ ...valid, min: '8', max: '2' }).errors.min).toBe('The minimum must be lower than the maximum.')
    expect(validate({ ...valid, min: '', max: '' }).errors.min).toBe('Set a minimum, a maximum or both.')
    expect(validate({ ...valid, max: 'abc' }).errors.max).toBe('Enter a number.')
    expect(validate({ ...valid, recipients: '' }).errors.recipients).toBe('Enter at least one email address.')
    expect(validate({ ...valid, recipients: 'ops@example' }).errors.recipients).toBe('Not a valid email address: ops@example')
    const eleven = Array.from({ length: 11 }, (_, i) => `r${i}@example.com`).join(',')
    expect(validate({ ...valid, recipients: eleven }).errors.recipients).toBe('Enter at most 10 email addresses.')
  })

  it('describes ranges and emails', () => {
    expect(rangeText(2, 8, '°C')).toBe('between 2 °C and 8 °C')
    expect(rangeText(null, 8, '°C')).toBe('at most 8 °C')
    expect(notificationSummary([
      { kind: 'ALERT', status: 'SENT', recipients: 1, attempts: 1, createdAt: '', sentAt: '', lastError: null },
      { kind: 'RESOLVED', status: 'FAILED', recipients: 1, attempts: 9, createdAt: '', sentAt: null, lastError: 'x' },
    ])).toEqual({ text: 'Alert sent, back to normal failed', failed: true })
  })
})
