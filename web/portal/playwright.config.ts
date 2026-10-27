import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests of the production build (npm run build) in a real browser, with Entra ID and the API faked at the
 * network level. Run with `npm run e2e` (or `npm run e2e:podman` where browsers cannot be installed locally).
 */
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4280',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node e2e/site-server.mjs',
    url: 'http://localhost:4280/index.html',
    reuseExistingServer: !process.env.CI,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
