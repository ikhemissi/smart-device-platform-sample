import type { DeviceDetails, DeviceEdit } from '../api/types'

/** Same limits as the API (device-catalog spec). */
export const LIMITS = { displayName: 100, description: 1000, label: 100 }

export interface DeviceForm {
  displayName: string
  description: string
  latitude: string
  longitude: string
  label: string
}

export type FormErrors = Partial<Record<keyof DeviceForm, string>>

export function toForm(device: DeviceDetails): DeviceForm {
  return {
    displayName: device.displayName ?? '',
    description: device.description ?? '',
    latitude: device.location ? String(device.location.latitude) : '',
    longitude: device.location ? String(device.location.longitude) : '',
    label: device.location?.label ?? '',
  }
}

/** Validates the form as the API does and builds the change, or returns the errors per field. */
export function toEdit(form: DeviceForm): { edit?: DeviceEdit; errors: FormErrors } {
  const errors: FormErrors = {}
  const text = (field: 'displayName' | 'description' | 'label', max: number) => {
    const value = form[field].trim()
    if (value.length > max) {
      errors[field] = `Use at most ${max} characters (currently ${value.length}).`
    }
    return value === '' ? null : value
  }
  const displayName = text('displayName', LIMITS.displayName)
  const description = text('description', LIMITS.description)
  const label = text('label', LIMITS.label)

  const coordinate = (field: 'latitude' | 'longitude', bound: number) => {
    const raw = form[field].trim().replace(',', '.')
    if (raw === '') {
      return undefined
    }
    const value = Number(raw)
    if (!Number.isFinite(value) || value < -bound || value > bound) {
      errors[field] = `Enter a number between -${bound} and ${bound}.`
      return undefined
    }
    return value
  }
  const latitude = coordinate('latitude', 90)
  const longitude = coordinate('longitude', 180)
  const anyLocation = form.latitude.trim() !== '' || form.longitude.trim() !== '' || label !== null
  if (anyLocation && form.latitude.trim() === '') {
    errors.latitude = 'Enter the latitude to set a location.'
  }
  if (anyLocation && form.longitude.trim() === '') {
    errors.longitude = 'Enter the longitude to set a location.'
  }
  if (Object.keys(errors).length > 0) {
    return { errors }
  }
  const location = latitude !== undefined && longitude !== undefined ? { latitude, longitude, label } : null
  return { edit: { displayName, description, location }, errors }
}

/** Maps API validation errors ("location.latitude") onto form fields. */
export function fromProblemErrors(errors: Record<string, string[]> | undefined): FormErrors {
  const result: FormErrors = {}
  for (const [path, messages] of Object.entries(errors ?? {})) {
    const field = path.replace(/^location\./, '') as keyof DeviceForm
    if (field in { displayName: 1, description: 1, latitude: 1, longitude: 1, label: 1 }) {
      result[field] = messages.join(' ')
    }
  }
  return result
}
