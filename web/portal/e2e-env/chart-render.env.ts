import { expect, test } from '../e2e/fixtures'
import { API, DEVICE, TOKEN, proxyToEnvironment } from './proxy'

/**
 * M3 task 4.6: 30-day chart render time in Chromium against the deployed environment. The production build runs
 * locally (sign-in faked) and every API call is forwarded to the environment's API Management with a real access token,
 * so the data path (gateway, device API, cache, database) and chart rendering are the deployed ones.
 *   SDP_API_BASE_URL=… SDP_API_TOKEN=… SDP_DEVICE=… npx playwright test -c playwright.env.config.ts
 */
test.skip(!API || !TOKEN, 'SDP_API_BASE_URL and SDP_API_TOKEN are required')

test('30-day chart renders within 3 s, and within 1 s when selected again', async ({ page, platform }) => {
  test.setTimeout(120_000)
  await proxyToEnvironment(page)
  void platform
  await page.goto(`/devices/${DEVICE}?period=24h`)
  const chart = page.getByRole('img', { name: /Chart of/ })
  await expect(chart).toHaveAttribute('data-rendered', 'true', { timeout: 30_000 })

  const measure = async (label: string) => {
    const start = Date.now()
    await page.getByRole('button', { name: label }).click()
    await expect(chart).toHaveAttribute('data-series', /^1h\|/, { timeout: 30_000 })
    await expect(chart).toHaveAttribute('data-rendered', 'true', { timeout: 30_000 })
    return Date.now() - start
  }

  const cold = await measure('30 d')
  await page.getByRole('button', { name: '24 h' }).click()
  await expect(chart).toHaveAttribute('data-series', /^5m\|/)
  const warm = await measure('30 d')
  const points = Number(/:(\d+)$/.exec((await chart.getAttribute('data-series')) ?? '')?.[1])

  console.log(`30-day chart (${points} buckets): first selection ${cold} ms, selected again ${warm} ms`)
  test.info().annotations.push({ type: 'render', description: `cold ${cold} ms, warm ${warm} ms` })
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  expect(cold).toBeLessThan(3_000)
  expect(warm).toBeLessThan(1_000)
})
