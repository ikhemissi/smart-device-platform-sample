import { screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from '../../api/client'
import type { SiteCertificate } from '../../api/types'
import { renderPortal } from '../../test/render'
import { API, EDITOR, READER, requests } from '../../test/server'
import { checkFile } from './trust'

const day = 86_400_000
const iso = (offset: number) => new Date(Date.now() + offset).toISOString()

function certificate(siteId: string, fingerprintChar: string, status: SiteCertificate['status'], expiresIn: number, slot: SiteCertificate['slot'] = 'primary'): SiteCertificate {
  return {
    siteId,
    fingerprint: fingerprintChar.repeat(64),
    slot,
    subject: `CN=${siteId} CA ${fingerprintChar}`,
    issuer: `CN=${siteId} CA ${fingerprintChar}`,
    notBefore: iso(-300 * day),
    notAfter: iso(expiresIn),
    status,
    addedBy: 'deployment',
    addedByName: 'Deployment',
    addedAt: iso(-1 * day),
  }
}

/** lyon-01: one certificate expiring in 10 days; paris-01: one valid certificate. */
const CERTIFICATES = [certificate('lyon-01', 'a', 'expiring', 10 * day), certificate('paris-01', 'b', 'valid', 900 * day)]

function trustHandlers(certificates = CERTIFICATES) {
  const removed: string[] = []
  return {
    removed,
    handlers: [
      http.get(`${API}/site-certificates`, () => HttpResponse.json(certificates)),
      http.get(`${API}/sites/:siteId/certificates`, ({ params }) => HttpResponse.json(certificates.filter((c) => c.siteId === params.siteId))),
      http.get(`${API}/sites/:siteId/audit`, () => HttpResponse.json([])),
      http.delete(`${API}/sites/:siteId/certificates/:fp`, ({ params }) => {
        removed.push(String(params.fp))
        const cert = certificates.find((c) => c.fingerprint === params.fp)!
        return HttpResponse.json({ certificate: cert, deviceOperation: { id: 'op-1', siteId: cert.siteId, kind: 'DISABLE_DEVICES', status: 'DONE', deviceCount: 2 } })
      }),
    ],
  }
}

describe('sites', () => {
  it('lists sites with device count and an expiring warning', async () => {
    renderPortal('/sites', READER, trustHandlers())

    const lyon = (await screen.findByRole('link', { name: 'lyon-01' })).closest('tr')!
    expect(within(lyon).getByText('Expiring')).toBeInTheDocument()
    expect(lyon).toHaveTextContent('2')
    expect(within(screen.getByRole('link', { name: 'paris-01' }).closest('tr')!).getByText('Valid')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sites' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows an expiring warning on the site page', async () => {
    renderPortal('/sites/lyon-01', READER, trustHandlers())

    expect(await screen.findByText('Certificate expiring')).toBeInTheDocument()
    expect(screen.getByText(/Add its replacement now/)).toBeInTheDocument()
    const row = within(screen.getByRole('table', { name: 'Trusted certificates' })).getAllByRole('row')[1]
    expect(row).toHaveTextContent('CN=lyon-01 CA a')
    expect(row).toHaveTextContent('primary')
    expect(row).toHaveTextContent('Deployment')
  })

  it('hides trust changes from Readers', async () => {
    renderPortal('/sites/paris-01', READER, trustHandlers())
    await screen.findByRole('table', { name: 'Trusted certificates' })
    expect(screen.queryByRole('button', { name: 'Add certificate' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument()

    renderPortal('/sites', READER, trustHandlers())
    await screen.findAllByRole('link', { name: 'paris-01' })
    expect(screen.queryByRole('button', { name: 'Create site' })).not.toBeInTheDocument()
  })

  it('asks for confirmation before removing the last certificate and warns that devices are disabled', async () => {
    const trust = trustHandlers()
    const { user } = renderPortal('/sites/paris-01', EDITOR, trust)

    await user.click(await screen.findByRole('button', { name: 'Remove CN=paris-01 CA b' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove the last certificate of paris-01?' })
    expect(dialog).toHaveTextContent('all 1 device of paris-01 will be disabled')

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(trust.removed).toEqual([])

    await user.click(await screen.findByRole('button', { name: 'Remove CN=paris-01 CA b' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Remove and disable devices' }))

    expect(await screen.findByRole('status')).toHaveTextContent('2 device(s) disabled')
    expect(trust.removed).toEqual(['b'.repeat(64)])
  })

  it('uses plain wording when another certificate remains', async () => {
    const both = [certificate('lyon-01', 'a', 'expiring', 10 * day), certificate('lyon-01', 'c', 'valid', 700 * day, 'secondary')]
    const { user } = renderPortal('/sites/lyon-01', EDITOR, trustHandlers(both))

    const remove = await screen.findByRole('button', { name: 'Remove CN=lyon-01 CA a' })
    expect(screen.getByRole('button', { name: 'Add certificate' })).toBeDisabled()
    await user.click(remove)

    const dialog = await screen.findByRole('alertdialog', { name: 'Remove this certificate?' })
    expect(dialog).toHaveTextContent("The site's other certificate keeps working")
  })

  it('uploads the chosen certificate file and reports re-enabled devices', async () => {
    // Node's fetch cannot send jsdom's FormData: the client is intercepted here; the real multipart upload runs in a
    // browser in the Playwright tests.
    const added = certificate('paris-01', 'd', 'valid', 700 * day, 'secondary')
    const upload = vi.spyOn(ApiClient.prototype, 'addSiteCertificate').mockResolvedValue({
      certificate: added,
      deviceOperation: { id: 'op-2', siteId: 'paris-01', kind: 'ENABLE_DEVICES', status: 'DONE', deviceCount: 3 },
    })
    const { user } = renderPortal('/sites/paris-01', EDITOR, trustHandlers())

    await user.click(await screen.findByRole('button', { name: 'Add certificate' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a certificate to paris-01' })
    const file = new File(['-----BEGIN CERTIFICATE-----\nMII\n-----END CERTIFICATE-----\n'], 'ca.pem', { type: 'application/x-pem-file' })
    await user.upload(within(dialog).getByLabelText(/Certificate file/), file)
    await user.click(within(dialog).getByRole('button', { name: 'Add certificate' }))

    expect(await screen.findByRole('status')).toHaveTextContent('added. 3 device(s) re-enabled.')
    expect(upload).toHaveBeenCalledWith('paris-01', file)
  })

  it('shows why the server refused a certificate', async () => {
    vi.spyOn(ApiClient.prototype, 'addSiteCertificate').mockRejectedValue(
      new ApiError(400, { title: 'Certificate rejected', status: 400, detail: 'A certificate authority certificate is required; this is a device (end-entity) certificate.' }),
    )
    const { user } = renderPortal('/sites/paris-01', EDITOR, trustHandlers())

    await user.click(await screen.findByRole('button', { name: 'Add certificate' }))
    const dialog = await screen.findByRole('dialog')
    await user.upload(within(dialog).getByLabelText(/Certificate file/), new File(['x'], 'device.crt'))
    await user.click(within(dialog).getByRole('button', { name: 'Add certificate' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('certificate authority certificate is required')
  })

  it('creates a site and links to it', async () => {
    let created: unknown
    const handlers = [
      http.post(`${API}/sites`, async ({ request }) => {
        created = await request.json()
        return HttpResponse.json({ siteId: 'nice-01', displayName: 'Nice warehouse', certificateCount: 0 }, { status: 201 })
      }),
      ...trustHandlers().handlers,
    ]
    const { user, router } = renderPortal('/sites', EDITOR, { handlers })

    await user.click(await screen.findByRole('button', { name: 'Create site' }))
    const dialog = await screen.findByRole('dialog', { name: 'Create a site' })
    await user.type(within(dialog).getByRole('textbox', { name: /Site id/ }), 'Nice 01')
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Nice warehouse')
    await user.click(within(dialog).getByRole('button', { name: 'Create site' }))
    expect(await within(dialog).findByText(/lowercase letters, digits and single hyphens/)).toBeInTheDocument()
    expect(created).toBeUndefined()

    await user.clear(within(dialog).getByRole('textbox', { name: /Site id/ }))
    await user.type(within(dialog).getByRole('textbox', { name: /Site id/ }), 'nice-01')
    await user.click(within(dialog).getByRole('button', { name: 'Create site' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Site nice-01 created')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(created).toEqual({ siteId: 'nice-01', displayName: 'Nice warehouse' })
    await user.click(screen.getByRole('link', { name: 'open nice-01' }))
    expect(router.state.location.pathname).toBe('/sites/nice-01')
    expect(requests.length).toBeGreaterThan(0)
  })

  it('checks the file before uploading', () => {
    expect(checkFile(undefined)).toMatch(/Choose a certificate/)
    expect(checkFile(new File(['x'], 'key.txt'))).toMatch(/.pem, .crt/)
    expect(checkFile(new File([new Uint8Array(70_000)], 'big.pem'))).toMatch(/64 KB/)
    expect(checkFile(new File(['x'], 'ca.DER'))).toBeUndefined()
  })
})
