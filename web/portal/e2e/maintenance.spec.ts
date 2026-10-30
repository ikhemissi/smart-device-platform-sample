import AxeBuilder from '@axe-core/playwright'
import { type Page } from '@playwright/test'
import { EDITOR, READER, animationsSettled, expect, expectAppAccessible, test } from './fixtures'

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

test('an Editor plans a visit, then closes it with a comment', async ({ page, platform, cspViolations }) => {
  platform.user = EDITOR
  await page.goto('/devices')
  await page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name: 'Maintenance' }).click()

  await expect(page).toHaveURL(/\/maintenance$/)
  await expect(page.getByRole('heading', { name: 'Maintenance', level: 1 })).toBeVisible()
  const table = page.getByRole('table', { name: 'Maintenance queue' })
  const rows = table.locator('tbody tr')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0)).toHaveAttribute('data-status', 'open')
  await expect(rows.nth(0).getByText('86 %')).toBeVisible()
  await expect(rows.nth(0).getByText('Battery runs out in 4.6 days (2.6 V)')).toBeVisible()
  await expect(rows.nth(0).getByText(/Replace the battery of paris-01-ruuvi-001/)).toBeVisible()
  await expect(rows.nth(1).getByText('Silent for 42 min', { exact: true })).toBeVisible()
  await expectNoSeriousViolations(page)

  await rows.nth(0).getByRole('button', { name: 'Mark the visit to paris-01-ruuvi-001 planned' }).click()
  let dialog = page.getByRole('dialog', { name: 'Plan this visit' })
  await expectNoSeriousViolations(page)
  await dialog.getByRole('button', { name: 'Mark planned' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText('paris-01-ruuvi-001: visit planned.')
  await expect(rows.nth(0)).toHaveAttribute('data-status', 'planned')

  await rows.nth(0).getByRole('button', { name: 'Mark the visit to paris-01-ruuvi-001 done' }).click()
  dialog = page.getByRole('dialog', { name: 'Close this visit' })
  await dialog.getByRole('textbox', { name: /Comment/ }).fill('Battery replaced')
  await expect(dialog).toContainText('16/1000 characters')
  await dialog.getByRole('button', { name: 'Mark done' }).click()

  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expectAppAccessible(page)
  await expect(rows).toHaveCount(1)
  expect(platform.maintenanceChanges).toEqual([
    { id: 1, status: 'planned', comment: null },
    { id: 1, status: 'done', comment: 'Battery replaced' },
  ])

  await page.getByRole('combobox', { name: 'Status' }).click()
  await page.getByRole('menuitemcheckbox', { name: 'Done' }).click()
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/status=open%2Cplanned%2Cdone|status=open,planned,done/)
  const done = table.locator('tbody tr[data-status="done"]')
  await expect(done).toHaveCount(2)
  await expect(done.getByText('“Battery replaced”')).toBeVisible()
  await expect(done.getByText(`by ${EDITOR.name}`).first()).toBeVisible()
  expect(cspViolations).toEqual([])
})

test('Readers open a queued device and see its battery and maintenance card, without actions', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/maintenance?siteId=paris-01')

  const table = page.getByRole('table', { name: 'Maintenance queue' })
  await expect(table.locator('tbody tr')).toHaveCount(2)
  await expect(page.getByRole('button', { name: /^Mark the visit/ })).toHaveCount(0)
  await table.getByRole('link', { name: 'paris-01-ruuvi-001' }).click()

  await expect(page).toHaveURL(/\/devices\/paris-01-ruuvi-001$/)
  await expect(page.getByRole('heading', { name: 'Battery and maintenance', level: 2 })).toBeVisible()
  await expect(page.getByText('-0.022 V/day')).toBeVisible()
  const item = page.getByRole('table', { name: 'Maintenance item' })
  await expect(item.locator('tbody tr[data-status="open"]')).toHaveCount(1)
  await expectNoSeriousViolations(page)
})

test('the device page says when the site is not assessed', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/devices/lyon-01-ruuvi-001')

  await expect(page.getByRole('heading', { name: 'Battery and maintenance', level: 2 })).toBeVisible()
  await expect(page.getByText('Maintenance is not assessed for this site.')).toBeVisible()
})
