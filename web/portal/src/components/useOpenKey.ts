import { useState } from 'react'

/**
 * Key for a Fluent UI Dialog driven by `open`: a new key each time it opens, so every opening mounts a fresh dialog that
 * starts open. The dialog stays mounted while it closes, so Fluent removes the aria-hidden it put on the rest of the app
 * (unmounting an open modal leaves the app hidden from assistive technology).
 */
export function useOpenKey(open: boolean): number {
  const [key, setKey] = useState(0)
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setKey((k) => k + 1)
    }
  }
  return key
}
