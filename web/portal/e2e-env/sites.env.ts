import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, expectAppAccessible, test } from '../e2e/fixtures'
import { API, TOKEN, proxyToEnvironment } from './proxy'

/** Test site created by this test and removed afterwards by scripts/e2e-environment.sh (scripts/remove-test-site.sh). */
const SITE = process.env.SDP_E2E_SITE ?? ''

test.skip(!API || !TOKEN || !SITE, 'SDP_API_BASE_URL, SDP_API_TOKEN and SDP_E2E_SITE are required')

/** A fresh self-signed CA (P-256, valid 30 days), as a site operator would upload. */
function generateCa(commonName: string) {
  const dir = mkdtempSync(join(tmpdir(), 'sdp-ca-'))
  execFileSync('openssl', [
    'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-days', '30',
    '-keyout', join(dir, 'ca.key'), '-out', join(dir, 'ca.pem'), '-subj', `/CN=${commonName}`,
    '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
  ], { stdio: 'ignore' })
  return { name: `${SITE}-ca.pem`, mimeType: 'application/x-pem-file', buffer: readFileSync(join(dir, 'ca.pem')) }
}

/** M4 task 4.3: create a site, trust a generated CA in DPS through the platform API, then remove it. */
test('an Editor creates a site, trusts a generated CA and removes it', async ({ page, platform }) => {
  test.setTimeout(120_000)
  void platform
  await proxyToEnvironment(page)
  const subject = `CN=${SITE} e2e CA`

  await page.goto('/sites')
  await page.getByRole('button', { name: 'Create site' }).click()
  const create = page.getByRole('dialog', { name: 'Create a site' })
  await create.getByRole('textbox', { name: /Site id/ }).fill(SITE)
  await create.getByRole('textbox', { name: /Name/ }).fill('E2E test site')
  await create.getByRole('button', { name: 'Create site' }).click()
  await expect(page.getByRole('status')).toContainText(`Site ${SITE} created`)
  await expectAppAccessible(page)

  await page.getByRole('link', { name: `open ${SITE}` }).click()
  await expect(page.getByRole('heading', { name: `E2E test site (${SITE})`, level: 1 })).toBeVisible()
  await expect(page.getByText('This site trusts no certificate')).toBeVisible()

  await page.getByRole('button', { name: 'Add certificate' }).click()
  const upload = page.getByRole('dialog', { name: `Add a certificate to ${SITE}` })
  await upload.getByLabel('Certificate file').setInputFiles(generateCa(`${SITE} e2e CA`))
  await upload.getByRole('button', { name: 'Add certificate' }).click()
  await expect(page.getByRole('status')).toContainText('added', { timeout: 30_000 })
  await expectAppAccessible(page)
  const certificates = page.getByRole('table', { name: 'Trusted certificates' })
  await expect(certificates.getByRole('row')).toHaveCount(2)
  await expect(certificates).toContainText(subject)
  await expect(certificates).toContainText('Valid')

  await page.getByRole('button', { name: `Remove ${subject}` }).click()
  const confirm = page.getByRole('alertdialog', { name: `Remove the last certificate of ${SITE}?` })
  await expect(confirm).toContainText('will be disabled')
  await confirm.getByRole('button', { name: 'Remove and disable devices' }).click()
  await expect(page.getByRole('status')).toContainText('removed', { timeout: 30_000 })
  await expectAppAccessible(page)
  await expect(page.getByText('This site trusts no certificate')).toBeVisible()

  const changes = page.getByRole('table', { name: 'Recent changes' })
  await expect(changes).toContainText('Certificate removed')
  await expect(changes).toContainText('Certificate added')
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})
