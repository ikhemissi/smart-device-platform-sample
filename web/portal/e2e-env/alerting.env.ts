import { expect, test } from '../e2e/fixtures'
import { API, TOKEN, proxyToEnvironment } from './proxy'

/** A device of a monitored site (default subscription filter: siteId = 'paris-01'). */
const DEVICE = process.env.SDP_ALERT_DEVICE || ''
const RECIPIENT = process.env.SDP_ALERT_RECIPIENT || 'e2e-alerts@example.com'

test.skip(!API || !TOKEN || !DEVICE, 'SDP_API_BASE_URL, SDP_API_TOKEN and SDP_ALERT_DEVICE are required')

/**
 * M5 task 5.2: an Editor adds a threshold (temperature at most 28 °C; the simulated rooms report 13–23 °C) to a monitored device whose simulator anomaly
 * (`SDP_SIM_ANOMALIES=<device>:temperature=35:5m:<delay>`, see docs/demo/m5-threshold-alerting.md) starts after the
 * threshold exists; the open alert appears on the device page. The threshold is deleted afterwards, which closes the
 * alert without email.
 */
test('an anomaly on a monitored device opens an alert visible on its page', async ({ page, platform }) => {
  test.setTimeout(15 * 60_000)
  void platform
  await proxyToEnvironment(page)
  await page.goto(`/devices/${DEVICE}`)
  await expect(page.getByRole('heading', { name: 'Thresholds', level: 2 })).toBeVisible()

  await page.getByRole('button', { name: 'Add threshold' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a threshold' })
  await dialog.getByRole('textbox', { name: /Maximum/ }).fill('28')
  await dialog.getByRole('textbox', { name: /Email recipients/ }).fill(RECIPIENT)
  await dialog.getByRole('button', { name: 'Add threshold' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const thresholds = page.getByRole('table', { name: 'Thresholds' })
  await expect(thresholds.getByText('at most 28 °C')).toBeVisible()
  const createdAt = Date.now()
  console.log(`Threshold created on ${DEVICE} at ${new Date(createdAt).toISOString()}; waiting for the anomaly`)

  try {
    const open = page.getByRole('table', { name: 'Alerts' }).locator('tr[data-status="OPEN"]')
    await expect(open).toHaveCount(1, { timeout: 12 * 60_000 })
    await expect(open).toContainText('above 28 °C')
    await expect(thresholds.getByText('Alerting')).toBeVisible({ timeout: 60_000 })
    console.log(`Open alert shown ${Math.round((Date.now() - createdAt) / 1000)} s after the threshold was created`)
  } finally {
    await page.getByRole('button', { name: /^Delete threshold on Air temperature/ }).click()
    await page.getByRole('alertdialog', { name: 'Delete this threshold?' }).getByRole('button', { name: 'Delete threshold' }).click()
    await expect(page.getByText('No threshold: this device never raises alerts.')).toBeVisible()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})
