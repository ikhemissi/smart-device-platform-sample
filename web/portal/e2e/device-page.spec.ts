import AxeBuilder from '@axe-core/playwright'
import { READER, expect, test } from './fixtures'

const DEVICE = 'paris-01-ruuvi-001'

test('opens a device from the list and switches periods', async ({ page, platform, cspViolations }) => {
  platform.user = READER
  await page.goto('/devices?siteId=paris-01')

  await page.getByRole('link', { name: DEVICE }).click()

  await expect(page).toHaveURL(new RegExp(`/devices/${DEVICE}$`))
  await expect(page.getByRole('heading', { name: DEVICE, level: 1 })).toBeVisible()
  const chart = page.getByRole('img', { name: /Chart of Air temperature/ })
  await expect(chart.locator('canvas').first()).toBeVisible()
  await expect(page.getByText('5-minute averages with minimum–maximum range')).toBeVisible()

  await page.getByRole('button', { name: '7 d' }).click()
  await expect(page).toHaveURL(/period=7d/)
  await expect(page.getByText('15-minute averages with minimum–maximum range')).toBeVisible()

  await page.getByRole('button', { name: '1 h' }).click()
  await expect(page.getByText('Every measurement')).toBeVisible()
  await expect(page.getByRole('table', { name: 'Latest measurements' }).getByRole('row')).toHaveCount(21)
  expect(cspViolations).toEqual([])
})

test('a new measurement appears in the table within a refresh', async ({ page, platform }) => {
  platform.user = READER
  await page.clock.install()
  await page.goto(`/devices/${DEVICE}`)
  const table = page.getByRole('table', { name: 'Latest measurements' })
  await expect(table.getByRole('row')).toHaveCount(21)

  const measuredAt = platform.report(DEVICE)
  await page.clock.runFor(30_000)

  const newest = table.getByRole('row').nth(1)
  await expect(newest.locator(`time[datetime="${measuredAt}"]`)).toBeVisible()
  await expect(newest).toHaveAttribute('data-fresh', 'true')
})

test('the device page has no serious accessibility violations', async ({ page, platform }) => {
  platform.user = READER
  await page.goto(`/devices/${DEVICE}`)
  await expect(page.getByRole('img', { name: /Chart of/ }).locator('canvas').first()).toBeVisible()

  // Fluent UI's focus-management sentinels (Tabster) are focusable but aria-hidden on purpose.
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .exclude('[data-tabster-dummy]')
    .analyze()
  expect(
    results.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ') + ' ' + n.html.slice(0, 160)).join(' | ')}`),
  ).toEqual([])
})

test.describe('chart rendering', () => {
  test.use({ viewport: { width: 1280, height: 1000 }, timezoneId: 'UTC', locale: 'en-GB' })

  for (const [name, query] of [
    ['raw', 'metrics=temperature,humidity&period=custom&from=2026-10-20T08:00:00.000Z&to=2026-10-20T09:00:00.000Z'],
    ['aggregated', 'metrics=temperature,humidity&period=custom&from=2026-09-28T00:00:00.000Z&to=2026-10-28T00:00:00.000Z'],
  ]) {
    test(`${name} series`, async ({ page, platform }) => {
      platform.user = READER
      await page.goto(`/devices/${DEVICE}?${query}`)
      const chart = page.getByRole('img', { name: /Chart of Air temperature \(°C\), Relative humidity \(%\)/ })
      await expect(chart.locator('canvas').first()).toBeVisible()
      // Let ECharts finish its first frame.
      await page.waitForTimeout(500)

      await expect(chart).toHaveScreenshot(`chart-${name}.png`, { maxDiffPixelRatio: 0.01 })
    })
  }
})
