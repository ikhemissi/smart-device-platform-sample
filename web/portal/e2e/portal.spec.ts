import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import { EDITOR, NO_ROLE, READER, animationsSettled, expect, expectAppAccessible, test } from './fixtures'

async function axe(page: Page) {
  // Fluent UI's focus manager (Tabster) inserts aria-hidden focus sentinels as soon as a dialog is mounted.
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .exclude('[data-tabster-dummy]')
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`)
}

test('signs in, filters by site and edits a device name that persists', async ({ page, platform, cspViolations }) => {
  platform.user = EDITOR
  await page.goto('/devices?siteId=lyon-01')

  // Returned to the requested page after sign-in.
  await expect(page.getByRole('heading', { name: 'Devices', level: 1 })).toBeVisible()
  await expect(page).toHaveURL(/\/devices\?siteId=lyon-01$/)
  await expect(page.getByRole('banner')).toContainText('Erin Editor')
  await expect(page.getByRole('combobox', { name: 'Site' })).toHaveValue('lyon-01')
  await expect(page.getByRole('row')).toHaveCount(13)

  await page.getByRole('combobox', { name: 'Site' }).selectOption('paris-01')
  await expect(page).toHaveURL(/siteId=paris-01/)
  await expect(page.getByText('1–12 of 12 devices')).toBeVisible()

  await page.getByRole('button', { name: 'Edit paris-01-ruuvi-001' }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit paris-01-ruuvi-001' })
  await dialog.getByRole('textbox', { name: /Display name/ }).fill('Paris lobby')
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(dialog).toBeHidden()
  await expectAppAccessible(page)
  await expect(page.getByText('Paris lobby', { exact: true })).toBeVisible()

  await page.reload()
  await expect(page.getByText('Paris lobby', { exact: true })).toBeVisible()
  expect(platform.devices.find((d) => d.deviceId === 'paris-01-ruuvi-001')?.updatedBy?.displayName).toBe('Erin Editor')
  expect(new Set(platform.tokens)).toEqual(new Set([`Bearer access-token-for-${EDITOR.oid}`]))
  expect(cspViolations).toEqual([])
})

test('a Reader sees no edit action', async ({ page, platform, cspViolations }) => {
  platform.user = READER
  await page.goto('/')

  await expect(page.getByRole('row')).toHaveCount(26)
  await expect(page.getByRole('button', { name: /^Edit / })).toHaveCount(0)
  expect(cspViolations).toEqual([])
})

test('a user without role gets the access-denied page', async ({ page, platform, cspViolations }) => {
  platform.user = NO_ROLE
  await page.goto('/devices')

  await expect(page.getByRole('heading', { name: 'You do not have access to device data' })).toBeVisible()
  await expect(page.getByText('Query device data')).toBeVisible()
  await expect(page.getByRole('table')).toHaveCount(0)
  expect(await axe(page)).toEqual([])
  expect(cspViolations).toEqual([])
})

test('the device list and edit dialog are operable with the keyboard only', async ({ page, platform }) => {
  platform.user = EDITOR
  await page.goto('/devices')
  await expect(page.getByRole('row')).toHaveCount(26)

  // Skip link, then filters.
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused()
  const site = page.getByRole('combobox', { name: 'Site' })
  while (!(await site.evaluate((el) => el === document.activeElement))) await page.keyboard.press('Tab')
  await page.keyboard.press('ArrowDown')
  await expect(page).toHaveURL(/siteId=lyon-01/)

  await page.keyboard.press('Tab') // status
  await page.keyboard.press('Tab') // search
  await expect(page.getByRole('searchbox')).toBeFocused()
  await page.keyboard.type('cayenne')
  await expect(page).toHaveURL(/search=cayenne/)
  await expect(page.getByRole('row')).toHaveCount(5)

  // Sort by a column header.
  const lastSeen = page.getByRole('columnheader', { name: /Last seen/ })
  await lastSeen.getByRole('button').focus()
  await page.keyboard.press('Enter')
  await expect(lastSeen).toHaveAttribute('aria-sort', 'ascending')

  // Edit action and dialog.
  await page.getByRole('button', { name: /^Edit / }).first().focus()
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('textbox', { name: /Display name/ })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expectAppAccessible(page)

  // Paging (back to all devices).
  await page.goto('/devices')
  await page.getByRole('button', { name: 'Next' }).focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/page=2/)
  await expect(page.getByText(/26–36 of 36 devices/)).toBeVisible()
})

test('pages have no serious accessibility violations', async ({ page, platform }) => {
  platform.user = EDITOR
  await page.goto('/devices')
  await expect(page.getByRole('row')).toHaveCount(26)
  expect(await axe(page)).toEqual([])

  await page.getByRole('button', { name: /^Edit / }).first().click()
  await expect(page.getByRole('dialog').getByRole('textbox', { name: /Display name/ })).toBeVisible()
  await animationsSettled(page)
  expect(await axe(page)).toEqual([])
})
