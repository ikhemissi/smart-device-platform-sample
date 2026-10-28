import { expect, test } from '../e2e/fixtures'
import { API, DEVICE, TOKEN, proxyToEnvironment } from './proxy'

test.skip(!API || !TOKEN, 'SDP_API_BASE_URL and SDP_API_TOKEN are required')

/** M3 task 4.7: a live simulated device, period switches, and its next measurement appearing within 60 s of being sent. */
test('device page follows a live device', async ({ page, platform }) => {
  test.setTimeout(180_000)
  void platform
  await proxyToEnvironment(page)
  await page.goto('/devices?siteId=paris-01')
  await page.getByRole('link', { name: DEVICE }).click()
  await expect(page.getByRole('heading', { name: DEVICE, level: 1 })).toBeVisible()

  const chart = page.getByRole('img', { name: /Chart of Air temperature/ })
  for (const [label, resolution] of [['7 d', '15m'], ['30 d', '1h'], ['1 h', 'raw'], ['24 h', '5m']]) {
    await page.getByRole('button', { name: label }).click()
    await expect(chart).toHaveAttribute('data-series', new RegExp(`^${resolution}\\|`), { timeout: 15_000 })
    await expect(chart).toHaveAttribute('data-rendered', 'true', { timeout: 15_000 })
  }

  const table = page.getByRole('table', { name: 'Latest measurements' })
  await expect(table.getByRole('row')).toHaveCount(21)
  const newest = () => table.getByRole('row').nth(1).locator('time').first().getAttribute('datetime')
  const before = await newest()

  await expect.poll(newest, { timeout: 150_000, intervals: [1_000] }).not.toBe(before)
  const appearedAt = Date.now()
  const measuredAt = Date.parse((await newest())!)
  const lag = appearedAt - measuredAt
  console.log(`New measurement ${new Date(measuredAt).toISOString()} appeared after ${Math.round(lag / 1000)} s`)
  await expect(table.getByRole('row').nth(1)).toHaveAttribute('data-fresh', 'true')
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  expect(lag).toBeLessThan(60_000)
})
