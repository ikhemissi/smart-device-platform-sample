import {
  Body1,
  Button,
  Caption1,
  Card,
  MessageBar,
  MessageBarBody,
  MessageBarTitle,
  Spinner,
  Subtitle1,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Title2,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { AddRegular, ArrowLeftRegular, DeleteRegular } from '@fluentui/react-icons'
import type { UseQueryResult } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { useCanWrite, useSiteAudit, useSiteCertificates, useSites } from '../../api/queries'
import type { AuditEvent, SiteCertificate } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { Timestamp } from '../../components/Timestamp'
import { useFeatures } from '../../features'
import { formatAbsolute, formatRelative } from '../../format'
import { DigestCard } from './DigestCard'
import { RemoveCertificateDialog } from './RemoveCertificateDialog'
import { TrustBadge } from './TrustBadge'
import { UploadCertificateDialog } from './UploadCertificateDialog'
import { addedBy, certificateChangeText, shortFingerprint, trustStatus } from './trust'

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  header: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: tokens.spacingHorizontalL },
  back: { display: 'inline-flex', alignItems: 'center', gap: tokens.spacingHorizontalXS },
  cardHeader: { display: 'flex', alignItems: 'center', justifyContent: 'space-between' },
  mono: { fontFamily: tokens.fontFamilyMonospace },
})

const ACTIONS: Record<string, string> = {
  SITE_CREATED: 'Site created',
  CERTIFICATE_ADDED: 'Certificate added',
  CERTIFICATE_REMOVED: 'Certificate removed',
  DEVICES_DISABLED: 'Devices disabled',
  DEVICES_ENABLED: 'Devices re-enabled',
  RECONCILED_ADDED: 'Certificate found in provisioning',
  RECONCILED_REMOVED: 'Certificate no longer in provisioning',
}

/** A site's trust (its CA certificates, changed by Editors), its digest and its recent changes. */
export function SitePage() {
  const styles = useStyles()
  const { siteId = '' } = useParams()
  const canWrite = useCanWrite()
  const digests = useFeatures().digests
  const site = useSites().data?.find((s) => s.siteId === siteId)
  const certificates = useSiteCertificates(siteId)
  const audit = useSiteAudit(siteId)
  const [uploading, setUploading] = useState(false)
  const [removing, setRemoving] = useState<SiteCertificate>()
  const [result, setResult] = useState<string>()

  if (certificates.error instanceof ApiError && certificates.error.status === 404) {
    return <SiteNotFound siteId={siteId} />
  }

  const certs = certificates.data ?? []

  return (
    <section className={styles.page} aria-labelledby="site-title">
      <Link to="/sites" className={styles.back}>
        <ArrowLeftRegular aria-hidden /> Sites
      </Link>
      <div className={styles.header}>
        <Title2 as="h1" id="site-title">{site && site.displayName !== siteId ? `${site.displayName} (${siteId})` : siteId}</Title2>
        {certificates.data && <TrustBadge status={trustStatus(certs)} />}
        {site && <Caption1>{site.deviceCount} device(s)</Caption1>}
      </div>

      {result && (
        <MessageBar intent="success" role="status">
          <MessageBarBody>{result}</MessageBarBody>
        </MessageBar>
      )}
      <CertificateWarnings certificates={certs} />
      {certificates.data && certs.length === 0 && (
        <MessageBar intent="warning">
          <MessageBarBody>This site trusts no certificate: no device can join it{site?.deviceCount ? ' and its devices are disabled' : ''}.</MessageBarBody>
        </MessageBar>
      )}

      {digests && <DigestCard siteId={siteId} canWrite={canWrite} />}

      <CertificatesCard
        certificates={certificates}
        canWrite={canWrite}
        onAdd={() => {
          setResult(undefined)
          setUploading(true)
        }}
        onRemove={(certificate) => {
          setResult(undefined)
          setRemoving(certificate)
        }}
      />
      <RecentChangesCard audit={audit} />

      <UploadCertificateDialog
        open={uploading}
        siteId={siteId}
        onClose={() => setUploading(false)}
        onAdded={(change) => {
          setUploading(false)
          setResult(certificateChangeText(change, 'added'))
        }}
      />
      <RemoveCertificateDialog
        certificate={removing}
        last={certs.length === 1}
        deviceCount={site?.deviceCount}
        onClose={() => setRemoving(undefined)}
        onRemoved={(change) => {
          setRemoving(undefined)
          setResult(certificateChangeText(change, 'removed'))
        }}
      />
    </section>
  )
}

function SiteNotFound({ siteId }: { siteId: string }) {
  const styles = useStyles()
  return (
    <section className={styles.page}>
      <Title2 as="h1">Site not found</Title2>
      <Body1 as="p">
        No site <strong>{siteId}</strong> exists. <Link to="/sites">Back to the sites</Link>
      </Body1>
    </section>
  )
}

/** One message per expired or expiring certificate. */
function CertificateWarnings({ certificates }: { certificates: SiteCertificate[] }) {
  return certificates
    .filter((c) => c.status !== 'valid')
    .map((c) => (
      <MessageBar key={c.fingerprint} intent={c.status === 'expired' ? 'error' : 'warning'} layout="multiline">
        <MessageBarBody>
          <MessageBarTitle>{c.status === 'expired' ? 'Certificate expired' : 'Certificate expiring'}</MessageBarTitle>
          {c.subject} {c.status === 'expired' ? 'expired' : 'expires'} {formatRelative(c.notAfter)} ({formatAbsolute(c.notAfter)}).
          {c.status === 'expiring' && ' Add its replacement now: the site can trust two certificates during the renewal.'}
        </MessageBarBody>
      </MessageBar>
    ))
}

function CertificatesCard({
  certificates,
  canWrite,
  onAdd,
  onRemove,
}: {
  certificates: UseQueryResult<SiteCertificate[]>
  canWrite: boolean
  onAdd: () => void
  onRemove: (certificate: SiteCertificate) => void
}) {
  const styles = useStyles()
  const certs = certificates.data ?? []
  const full = certs.length >= 2
  return (
    <Card>
      <div className={styles.cardHeader}>
        <Subtitle1 as="h2">Trusted certificates</Subtitle1>
        {canWrite && certificates.data && (
          <Button
            icon={<AddRegular />}
            disabled={full}
            title={full ? 'A site trusts at most two certificates; remove one first.' : undefined}
            onClick={onAdd}
          >
            Add certificate
          </Button>
        )}
      </div>
      {certificates.isError && !certificates.data ? (
        <ErrorState title="The certificates could not be loaded" error={certificates.error} onRetry={() => void certificates.refetch()} />
      ) : !certificates.data ? (
        <Spinner label="Loading certificates…" />
      ) : certs.length === 0 ? (
        <Body1>None.</Body1>
      ) : (
        <Table aria-label="Trusted certificates">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>Certificate authority</TableHeaderCell>
              <TableHeaderCell>Slot</TableHeaderCell>
              <TableHeaderCell>Valid until</TableHeaderCell>
              <TableHeaderCell>Status</TableHeaderCell>
              <TableHeaderCell>Added</TableHeaderCell>
              {canWrite && <TableHeaderCell><span className="visually-hidden">Actions</span></TableHeaderCell>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {certs.map((c) => (
              <TableRow key={c.fingerprint}>
                <TableCell>
                  <div>{c.subject}</div>
                  <Caption1 className={styles.mono} title={c.fingerprint}>
                    SHA-256 {shortFingerprint(c.fingerprint)}
                  </Caption1>
                </TableCell>
                <TableCell>{c.slot}</TableCell>
                <TableCell>
                  <Timestamp value={c.notAfter} />
                </TableCell>
                <TableCell>
                  <TrustBadge status={c.status} />
                </TableCell>
                <TableCell>
                  {addedBy(c)}
                  <br />
                  <Caption1>{formatAbsolute(c.addedAt)}</Caption1>
                </TableCell>
                {canWrite && (
                  <TableCell>
                    <Button appearance="subtle" icon={<DeleteRegular />} aria-label={`Remove ${c.subject}`} onClick={() => onRemove(c)} />
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Card>
  )
}

function RecentChangesCard({ audit }: { audit: UseQueryResult<AuditEvent[]> }) {
  const styles = useStyles()
  return (
    <Card>
      <Subtitle1 as="h2">Recent changes</Subtitle1>
      {!audit.data ? (
        audit.isError ? <Body1>The change history could not be loaded.</Body1> : <Spinner size="tiny" label="Loading…" />
      ) : audit.data.length === 0 ? (
        <Body1>No change recorded yet.</Body1>
      ) : (
        <Table aria-label="Recent changes" size="small">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>When</TableHeaderCell>
              <TableHeaderCell>Change</TableHeaderCell>
              <TableHeaderCell>By</TableHeaderCell>
              <TableHeaderCell>Certificate</TableHeaderCell>
            </TableRow>
          </TableHeader>
          <TableBody>
            {audit.data.map((event) => (
              <TableRow key={event.id}>
                <TableCell>
                  <Timestamp value={event.occurredAt} />
                </TableCell>
                <TableCell>
                  {ACTIONS[event.action] ?? event.action}
                  {typeof event.details.devices === 'number' ? ` (${event.details.devices})` : ''}
                </TableCell>
                <TableCell>{event.actorName ?? event.actorId}</TableCell>
                <TableCell className={styles.mono}>{event.fingerprint ? shortFingerprint(event.fingerprint) : '–'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Card>
  )
}
