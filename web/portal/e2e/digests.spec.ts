import AxeBuilder from '@axe-core/playwright'
import { type Page } from '@playwright/test'
import { EDITOR, READER, animationsSettled, expect, test } from './fixtures'

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

const digestCard = (page: Page) => page.locator('.fui-Card').filter({ has: page.getByRole('heading', { name: 'Digest', level: 2 }) })

test('Readers read the digest of a site and open a device it mentions', async ({ page, platform, cspViolations }) => {
  platform.user = READER
  await page.goto('/sites/paris-01')

  const card = digestCard(page)
  const brief = card.getByText(/^Over the last 24 hours/)
  await expect(brief).toContainText('11 of the 12 devices of paris-01 reported; paris-01-cayenne-006 went silent.')
  await expect(card.getByRole('table', { name: 'Metrics' }).getByRole('row', { name: /temperature/ })).toContainText('+0.2')
  await expect(card.getByRole('region', { name: 'Devices' })).toContainText('11 of 12 reporting, 1 silent:')
  await expect(card.getByRole('region', { name: 'Anomalies' })).toContainText('By classification: Real event: 1, Sensor fault: 1')
  await expect(card.getByRole('region', { name: 'Rejected messages' })).toContainText('None.')
  await expect(card.getByRole('button', { name: 'Regenerate' })).toHaveCount(0)
  await expectNoSeriousViolations(page)

  await brief.getByRole('link', { name: 'paris-01-ruuvi-001' }).click()
  await expect(page).toHaveURL(/\/devices\/paris-01-ruuvi-001$/)
  await expect(page.getByRole('heading', { name: 'paris-01-ruuvi-001', level: 1 })).toBeVisible()
  expect(cspViolations).toEqual([])
})

test('an Editor regenerates a digest and the new one replaces it without a reload', async ({ page, platform }) => {
  // Fake timers: the card refreshes every 20 seconds while the request is pending.
  await page.clock.install()
  platform.user = EDITOR
  await page.goto('/sites/paris-01')

  const card = digestCard(page)
  await expect(card.getByText(/^Over the last 24 hours/)).toBeVisible()
  await card.getByRole('button', { name: 'Regenerate' }).click()

  await expect(card.getByRole('status')).toContainText(`A new digest is being generated (requested by ${EDITOR.name} at`)
  await expect(card.getByRole('button', { name: 'Regenerate' })).toBeDisabled()
  expect([...platform.digestRequests.keys()]).toEqual(['paris-01'])
  await expectNoSeriousViolations(page)

  platform.serveDigestRequests('A quiet day at paris-01: every device reported.')
  await page.clock.fastForward(20_000)

  await expect(card.getByText('A quiet day at paris-01: every device reported.')).toBeVisible()
  await expect(card.getByText(new RegExp(`on request of ${EDITOR.name}`))).toBeVisible()
  await expect(card.getByRole('status')).toHaveCount(0)
  await expect(card.getByRole('button', { name: 'Regenerate' })).toBeEnabled()
})

test('a digest without brief shows its figures, and a site without digests says so', async ({ page, platform }) => {
  platform.user = READER
  await page.goto('/sites/lyon-01')

  const card = digestCard(page)
  await expect(card.getByText('Brief unavailable')).toBeVisible()
  await expect(card.getByRole('table', { name: 'Metrics' })).toBeVisible()
  await expectNoSeriousViolations(page)

  await page.goto('/sites/marseille-01')
  await expect(digestCard(page).getByText(/This site has no digest/)).toBeVisible()
})
