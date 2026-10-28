import AxeBuilder from '@axe-core/playwright'
import { EDITOR, READER, expect, expectAppAccessible, test } from './fixtures'

/** Stand-in CA file for the faked API (the real API validates real certificates: see e2e-env/sites.env.ts). */
const CA_FILE = { name: 'nice-01-ca.pem', mimeType: 'application/x-pem-file', buffer: Buffer.from('-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n') }

test('an Editor creates a site, trusts a CA and removes it after confirmation', async ({ page, platform, cspViolations }) => {
  platform.user = EDITOR
  await page.goto('/sites')

  await page.getByRole('button', { name: 'Create site' }).click()
  const create = page.getByRole('dialog', { name: 'Create a site' })
  await create.getByRole('textbox', { name: /Site id/ }).fill('nice-01')
  await create.getByRole('textbox', { name: /Name/ }).fill('Nice warehouse')
  await create.getByRole('button', { name: 'Create site' }).click()

  await expect(page.getByRole('status')).toContainText('Site nice-01 created')
  await expectAppAccessible(page)
  await page.getByRole('link', { name: 'open nice-01' }).click()
  await expect(page).toHaveURL(/\/sites\/nice-01$/)
  await expect(page.getByRole('heading', { name: 'Nice warehouse (nice-01)', level: 1 })).toBeVisible()
  await expect(page.getByText('This site trusts no certificate')).toBeVisible()

  await page.getByRole('button', { name: 'Add certificate' }).click()
  const upload = page.getByRole('dialog', { name: 'Add a certificate to nice-01' })
  await upload.getByLabel('Certificate file').setInputFiles(CA_FILE)
  await upload.getByRole('button', { name: 'Add certificate' }).click()
  await expect(page.getByRole('status')).toContainText('added')
  await expectAppAccessible(page)
  await expect(page.getByRole('table', { name: 'Trusted certificates' }).getByRole('row')).toHaveCount(2)
  expect(platform.certificates.map((c) => c.siteId)).toEqual(['nice-01'])

  await page.getByRole('button', { name: 'Remove CN=nice-01 uploaded CA' }).click()
  const confirm = page.getByRole('alertdialog', { name: 'Remove the last certificate of nice-01?' })
  await expect(confirm).toContainText('will be disabled')
  await confirm.getByRole('button', { name: 'Remove and disable devices' }).click()

  await expect(page.getByRole('status')).toContainText('removed')
  await expectAppAccessible(page)
  await expect(page.getByText('This site trusts no certificate')).toBeVisible()
  await expect(page.getByRole('table', { name: 'Recent changes' })).toContainText('Certificate removed')
  expect(cspViolations).toEqual([])
})

test('Readers see trust status without actions', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/sites')

  await expect(page.getByRole('table', { name: 'Sites' }).getByRole('row')).toHaveCount(4)
  await expect(page.getByRole('button', { name: 'Create site' })).toHaveCount(0)
  await page.getByRole('link', { name: 'lyon-01' }).click()
  await expect(page.getByRole('heading', { name: 'lyon-01', level: 1 })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add certificate' })).toHaveCount(0)
})

test('the sites pages have no serious accessibility violations', async ({ page, platform }) => {
  platform.user = EDITOR
  for (const path of ['/sites', '/sites/paris-01']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByText(/Loading/)).toHaveCount(0)
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).exclude('[data-tabster-dummy]').analyze()
    expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${path} ${v.id}`)).toEqual([])
  }
  await page.getByRole('button', { name: 'Add certificate' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  const dialog = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).exclude('[data-tabster-dummy]').analyze()
  expect(dialog.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => v.id)).toEqual([])
})
