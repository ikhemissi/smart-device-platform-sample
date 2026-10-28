import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

// jsdom has no layout: Fluent UI components that observe their size (MessageBar, overflow) get a no-op observer.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver

// jsdom has no Web Animations API: Fluent UI motion (dialog enter/exit) needs animations that finish at once.
if (!('animate' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    value() {
      const animation = {
        finished: Promise.resolve(),
        onfinish: null as null | (() => void),
        oncancel: null,
        playState: 'finished',
        currentTime: 0,
        playbackRate: 1,
        effect: null,
        cancel() {},
        finish() {},
        play() {},
        pause() {},
        reverse() {},
        persist() {},
        commitStyles() {},
        addEventListener() {},
        removeEventListener() {},
      }
      queueMicrotask(() => animation.onfinish?.())
      return animation
    },
  })
}

// jsdom has no layout: offsetParent is always null and <body> measures 0x0, so Fluent UI's focus manager (Tabster) finds
// no visible element. An opening dialog then focuses its own surface instead of its first button, the modal is never
// activated, and Tabster marks the dialog itself aria-hidden. Give <body> the window's size and report the parent as
// offsetParent, unless the element is not rendered.
HTMLBodyElement.prototype.getBoundingClientRect = function () {
  return DOMRect.fromRect({ x: 0, y: 0, width: window.innerWidth, height: window.innerHeight })
}
Object.defineProperty(HTMLElement.prototype, 'offsetParent', {
  configurable: true,
  get(this: HTMLElement) {
    for (let e = this as HTMLElement | null; e; e = e.parentElement) {
      if (e.hidden || getComputedStyle(e).display === 'none') {
        return null
      }
    }
    return this.parentElement
  },
})

// The whole portal renders per test: allow for slower machines and parallel test files.
configure({ asyncUtilTimeout: 5000 })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
})
afterAll(() => server.close())
