import { expect, test } from '../e2e/fixtures'
import { API, DEVICE, TOKEN, proxyToEnvironment } from './proxy'

test.skip(!API || !TOKEN, 'SDP_API_BASE_URL and SDP_API_TOKEN are required')

/**
 * M6 task 5.2: ask the assistant from a device page and follow a citation. The answer comes from the deployed agent
 * (model, tools, platform API); the proxy delivers the stream in one piece (streaming itself is checked at the gateway).
 */
test('assistant answers from a device page with citations', async ({ page, platform }) => {
  test.setTimeout(150_000)
  void platform
  await page.route('**/config.json', async (route) => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...(await response.json()), assistantEnabled: true } })
  })
  await proxyToEnvironment(page)
  await page.goto(`/devices/${DEVICE}`)
  await expect(page.getByRole('heading', { name: DEVICE, level: 1 })).toBeVisible()

  await page.getByRole('button', { name: 'Assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await expect(panel.getByText(DEVICE)).toBeVisible()
  await panel.getByRole('textbox', { name: 'Question' }).fill('How did the temperature evolve over the last 24 hours?')
  await panel.getByRole('button', { name: 'Send' }).click()

  await expect(panel.getByRole('list', { name: 'Data consulted' })).toContainText(`history of ${DEVICE}`, { timeout: 90_000 })
  const citation = panel.getByRole('link', { name: new RegExp(`^${DEVICE} ·`) })
  await expect(citation).toBeVisible({ timeout: 90_000 })
  await expect(panel.getByText('°C').first()).toBeVisible()
  console.log(`Answer: ${(await panel.locator('p, li').allTextContents()).join(' ').slice(0, 400)}`)

  await citation.click()
  await expect(page).toHaveURL(new RegExp(`/devices/${DEVICE}\\?period=custom&from=.+&to=.+&metrics=temperature`))
  await expect(page.getByRole('button', { name: 'Custom' })).toHaveAttribute('aria-pressed', 'true')
  await expect(panel).toBeVisible()
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})
