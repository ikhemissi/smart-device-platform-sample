import AxeBuilder from '@axe-core/playwright'
import { type Page } from '@playwright/test'
import { EDITOR, READER, animationsSettled, expect, expectAppAccessible, test } from './fixtures'

const DEVICE = 'paris-01-ruuvi-001'

/** WCAG 2.1 A/AA: no serious or critical violation (Fluent's focus sentinels are aria-hidden on purpose). */
async function expectNoSeriousViolations(page: Page) {
  await animationsSettled(page)
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).exclude('[data-tabster-dummy]').analyze()
  expect(
    results.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ') + ' ' + n.html.slice(0, 160)).join(' | ')}`),
  ).toEqual([])
}

test('an Editor reviews an anomaly to review from the Anomalies page', async ({ page, platform, cspViolations }) => {
  platform.user = EDITOR
  await page.goto('/devices')
  await page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name: 'Anomalies' }).click()

  await expect(page).toHaveURL(/\/anomalies$/)
  await expect(page.getByRole('heading', { name: 'Anomalies', level: 1 })).toBeVisible()
  const table = page.getByRole('table', { name: 'Anomalies' })
  const rows = table.locator('tbody tr')
  await expect(rows).toHaveCount(3)
  await expect(rows.nth(0)).toHaveAttribute('data-status', 'to_review')
  await expect(rows.nth(0).getByText('To review')).toBeVisible()
  await expect(rows.nth(0).getByText('Sensor fault · 55 %')).toBeVisible()
  await expect(rows.nth(1).getByText('Real event · 91 %')).toBeVisible()
  await expectNoSeriousViolations(page)

  await rows.nth(0).getByRole('button', { name: /^Review/ }).click()
  const dialog = page.getByRole('dialog', { name: 'Review this anomaly' })
  await dialog.getByRole('radio', { name: 'Classification incorrect' }).check()
  await dialog.getByRole('textbox', { name: /Comment/ }).fill('Humidity sensor replaced that morning')
  await expect(dialog).toContainText('37/1000 characters')
  await expectNoSeriousViolations(page)
  await dialog.getByRole('button', { name: 'Save review' }).click()

  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expectAppAccessible(page)
  await expect(page.getByRole('status')).toContainText('Review saved')
  await expect(rows).toHaveCount(2)
  expect(platform.reviews).toEqual([{ id: 3, verdict: 'incorrect', comment: 'Humidity sensor replaced that morning' }])

  await page.getByRole('combobox', { name: 'Status' }).click()
  await page.getByRole('menuitemcheckbox', { name: 'Reviewed' }).click()
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/status=confirmed%2Cto_review%2Creviewed|status=confirmed,to_review,reviewed/)
  const reviewed = table.locator('tbody tr[data-status="reviewed"]')
  await expect(reviewed.getByText('Reviewed', { exact: true })).toBeVisible()
  await expect(reviewed.getByText('Classification incorrect')).toBeVisible()
  await expect(reviewed.getByText(`by ${EDITOR.name}`)).toBeVisible()
  expect(cspViolations).toEqual([])
})

test('Readers open the device page over an anomaly from the list, without review actions', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/anomalies?siteId=paris-01')

  const confirmed = page.getByRole('table', { name: 'Anomalies' }).locator('tbody tr[data-status="confirmed"]').first()
  await expect(confirmed.getByText('Real event · 91 %')).toBeVisible()
  await expect(page.getByRole('button', { name: /^Review/ })).toHaveCount(0)
  await confirmed.getByRole('link', { name: DEVICE }).click()

  await expect(page).toHaveURL(new RegExp(`/devices/${DEVICE}\\?metrics=temperature&period=custom&from=`))
  await expect(page.getByRole('img', { name: /Chart of Air temperature/ })).toBeVisible()
  const card = page.getByRole('table', { name: 'Anomalies' })
  await expect(card.locator('tbody tr')).toHaveCount(1)
  await expect(card.getByText('Real event · 91 %')).toBeVisible()
})

test('the device page lists the anomalies of the period, dismissed ones on request', async ({ page, platform }) => {
  platform.user = READER
  await page.goto(`/devices/${DEVICE}`)

  const card = page.getByRole('table', { name: 'Anomalies' })
  await expect(card.locator('tbody tr')).toHaveCount(2)
  const confirmed = card.locator('tbody tr[data-status="confirmed"]')
  await expect(confirmed.getByText('Real event · 91 %')).toBeVisible()
  await expect(confirmed.getByText('28.4–31.2 °C, last 30.9 °C')).toBeVisible()
  await expect(confirmed.getByText('usual 21 °C')).toBeVisible()
  await expect(confirmed.getByText(/A door left open is the likely cause\./)).toBeVisible()
  await expect(card.locator('tbody tr[data-status="to_review"]').getByText('To review')).toBeVisible()
  await expectNoSeriousViolations(page)

  await page.getByRole('switch', { name: 'Show dismissed' }).check()
  await expect(card.locator('tbody tr')).toHaveCount(3)
  await expect(card.locator('tbody tr[data-status="dismissed"]').getByText('Expected pattern · 88 %')).toBeVisible()
})

test('the device page says when the site is not analysed', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/devices/lyon-01-ruuvi-001')

  await expect(page.getByRole('heading', { name: 'Anomalies', level: 2 })).toBeVisible()
  await expect(page.getByText('Anomalies are not analysed for this site.')).toBeVisible()
})
