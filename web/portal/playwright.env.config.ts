import { defineConfig, devices } from '@playwright/test'
import base from './playwright.config'

/** Measurements against a deployed environment (network access and an API token required). */
export default defineConfig({
  ...base,
  testDir: 'e2e-env',
  testMatch: '**/*.env.ts',
  fullyParallel: false,
  workers: 1,
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
