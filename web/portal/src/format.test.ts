import { describe, expect, it } from 'vitest'
import { formatRelative } from './format'

describe('relative time', () => {
  const now = Date.parse('2026-10-27T12:00:00Z')

  it('uses the largest suitable unit', () => {
    expect(formatRelative('2026-10-27T11:55:00Z', now)).toMatch(/5 minutes ago/)
    expect(formatRelative('2026-10-27T11:59:30Z', now)).toMatch(/30 seconds ago/)
    expect(formatRelative('2026-10-26T12:00:00Z', now)).toMatch(/yesterday/)
  })
})
