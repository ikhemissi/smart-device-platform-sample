import type { CertificateStatus, SiteCertificate } from '../../api/types'

export type TrustStatus = CertificateStatus | 'none'

/** Worst status among a site's certificates: none, expired, expiring, valid (in that order of attention). */
export function trustStatus(certificates: SiteCertificate[]): TrustStatus {
  if (certificates.length === 0) return 'none'
  if (certificates.some((c) => c.status === 'expired')) return 'expired'
  if (certificates.some((c) => c.status === 'expiring')) return 'expiring'
  return 'valid'
}

export function nextExpiry(certificates: SiteCertificate[]): string | undefined {
  return certificates.map((c) => c.notAfter).sort()[0]
}

/** Readable "who added it". */
export function addedBy(certificate: SiteCertificate): string {
  if (certificate.addedBy === 'deployment') return 'Deployment'
  if (certificate.addedBy === 'reconciliation') return 'Reconciliation'
  return certificate.addedByName ?? certificate.addedBy
}

/** Fingerprint grouped for reading: `86aa be6f 0d59 …` (first 16 hex digits). */
export function shortFingerprint(fingerprint: string): string {
  return `${fingerprint.slice(0, 16).replace(/(.{4})/g, '$1 ').trim()} …`
}

export const SITE_ID_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

export const MAX_CERTIFICATE_BYTES = 64 * 1024
const EXTENSIONS = ['.pem', '.crt', '.cer', '.der']

/** Quick checks only; the server validates the certificate itself (CA, validity, no private key, duplicates). */
export function checkFile(file: File | undefined): string | undefined {
  if (!file) return 'Choose a certificate file.'
  if (!EXTENSIONS.some((ext) => file.name.toLowerCase().endsWith(ext))) return 'Choose a .pem, .crt, .cer or .der certificate file.'
  if (file.size > MAX_CERTIFICATE_BYTES) return 'The file is larger than 64 KB; upload a single certificate.'
  return undefined
}
