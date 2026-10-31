/** A copy of `params` with `changes` applied; an undefined or empty value removes the parameter. */
export function withParams(params: URLSearchParams, changes: Record<string, string | undefined>): URLSearchParams {
  const next = new URLSearchParams(params)
  for (const [key, value] of Object.entries(changes)) {
    if (value === undefined || value === '') {
      next.delete(key)
    } else {
      next.set(key, value)
    }
  }
  return next
}

/** The values of a comma-separated list parameter that are in `allowed` (in its order); `defaults` when there are none. */
export function readListParam<T extends string>(value: string | null, allowed: readonly T[], defaults: T[]): T[] {
  const values = (value ?? '').split(',')
  const selected = allowed.filter((item) => values.includes(item))
  return selected.length > 0 ? selected : defaults
}
