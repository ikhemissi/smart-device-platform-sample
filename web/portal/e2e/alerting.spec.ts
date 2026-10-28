import AxeBuilder from '@axe-core/playwright'
import { EDITOR, READER, expect, expectAppAccessible, test } from './fixtures'

const DEVICE = 'paris-01-ruuvi-001'

test('an Editor adds a threshold on the device page', async ({ page, platform, cspViolations }) => {
  platform.user = EDITOR
  await page.goto(`/devices/${DEVICE}`)

  await page.getByRole('button', { name: 'Add threshold' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a threshold' })
  await dialog.getByRole('textbox', { name: /Minimum/ }).fill('2')
  await dialog.getByRole('textbox', { name: /Maximum/ }).fill('8')
  await dialog.getByRole('textbox', { name: /Email recipients/ }).fill('ops@example.com')
  await expect(page.getByRole('dialog')).toBeVisible()
  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).exclude('[data-tabster-dummy]').analyze()
  expect(axe.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => v.id)).toEqual([])
  await dialog.getByRole('button', { name: 'Add threshold' }).click()

  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expectAppAccessible(page)
  const thresholds = page.getByRole('table', { name: 'Thresholds' })
  await expect(thresholds.getByText('between 2 °C and 8 °C')).toBeVisible()
  await expect(thresholds.getByText('Watching')).toBeVisible()
  expect(platform.thresholds).toEqual([expect.objectContaining({ deviceId: DEVICE, metric: 'temperature', min: 2, max: 8, recipients: ['ops@example.com'] })])
  expect(cspViolations).toEqual([])
})

test('Readers see alerting without threshold actions', async ({ page, platform }) => {
  platform.user = READER
  await page.goto(`/devices/${DEVICE}`)

  await expect(page.getByRole('heading', { name: 'Thresholds', level: 2 })).toBeVisible()
  await expect(page.getByText('No threshold: this device never raises alerts.')).toBeVisible()
  await expect(page.getByText('No alert in the last 30 days.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add threshold' })).toHaveCount(0)
})
