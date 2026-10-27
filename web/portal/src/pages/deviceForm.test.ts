import { describe, expect, it } from 'vitest'
import { fromProblemErrors, toEdit } from './deviceForm'

const empty = { displayName: '', description: '', latitude: '', longitude: '', label: '' }

describe('device form', () => {
  it('clears blank fields and the location', () => {
    expect(toEdit(empty).edit).toEqual({ displayName: null, description: null, location: null })
  })

  it('accepts boundaries and decimal commas', () => {
    expect(toEdit({ ...empty, latitude: '-90', longitude: '180,0' }).edit?.location).toEqual({ latitude: -90, longitude: 180, label: null })
  })

  it('rejects out-of-range and non-numeric coordinates', () => {
    expect(toEdit({ ...empty, latitude: 'north', longitude: '-180.1' }).errors).toEqual({
      latitude: 'Enter a number between -90 and 90.',
      longitude: 'Enter a number between -180 and 180.',
    })
  })

  it('maps API field paths', () => {
    expect(fromProblemErrors({ 'location.label': ['too long'], siteId: ['is not editable'] })).toEqual({ label: 'too long' })
  })
})
