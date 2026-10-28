import { Body1, Button, MessageBar, MessageBarBody, Spinner, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Title2, makeStyles, tokens } from '@fluentui/react-components'
import { AddRegular } from '@fluentui/react-icons'
import { useState } from 'react'
import { Link } from 'react-router'
import { useAllSiteCertificates, useCurrentUser, useSites } from '../../api/queries'
import { ErrorState } from '../../components/ErrorState'
import { formatAbsolute, formatRelative, numberFormat } from '../../format'
import { CreateSiteDialog } from './CreateSiteDialog'
import { TrustBadge } from './TrustBadge'
import { nextExpiry, trustStatus } from './trust'

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between' },
  link: { color: tokens.colorBrandForegroundLink, ':hover': { textDecoration: 'underline' } },
})

export function SitesPage() {
  const styles = useStyles()
  const canWrite = useCurrentUser().data?.canWrite === true
  const sites = useSites()
  const certificates = useAllSiteCertificates()
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<string>()

  const error = sites.error ?? certificates.error
  return (
    <section className={styles.page} aria-labelledby="sites-title">
      <div className={styles.header}>
        <Title2 as="h1" id="sites-title">Sites</Title2>
        {canWrite && (
          <Button appearance="primary" icon={<AddRegular />} onClick={() => setCreating(true)}>
            Create site
          </Button>
        )}
      </div>
      {created && (
        <MessageBar intent="success" role="status">
          <MessageBarBody>
            Site {created} created. Add a certificate so its devices can join:{' '}
            <Link to={`/sites/${encodeURIComponent(created)}`} className={styles.link}>
              open {created}
            </Link>
          </MessageBarBody>
        </MessageBar>
      )}
      {error && !(sites.data && certificates.data) ? (
        <ErrorState title="The sites could not be loaded" error={error} onRetry={() => void Promise.all([sites.refetch(), certificates.refetch()])} />
      ) : !sites.data || !certificates.data ? (
        <Spinner label="Loading sites…" />
      ) : sites.data.length === 0 ? (
        <Body1>No site yet.</Body1>
      ) : (
        <Table aria-label="Sites">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>Site</TableHeaderCell>
              <TableHeaderCell>Devices</TableHeaderCell>
              <TableHeaderCell>Certificates</TableHeaderCell>
              <TableHeaderCell>Trust</TableHeaderCell>
              <TableHeaderCell>Next expiry</TableHeaderCell>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sites.data.map((site) => {
              const certs = certificates.data.filter((c) => c.siteId === site.siteId)
              const expiry = nextExpiry(certs)
              return (
                <TableRow key={site.siteId}>
                  <TableCell>
                    <Link to={`/sites/${encodeURIComponent(site.siteId)}`} className={styles.link}>
                      {site.displayName === site.siteId ? site.siteId : `${site.displayName} (${site.siteId})`}
                    </Link>
                  </TableCell>
                  <TableCell>{numberFormat.format(site.deviceCount)}</TableCell>
                  <TableCell>{certs.length}</TableCell>
                  <TableCell>
                    <TrustBadge status={trustStatus(certs)} />
                  </TableCell>
                  <TableCell>{expiry ? <span title={formatAbsolute(expiry)}>{formatRelative(expiry)}</span> : '–'}</TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
      <CreateSiteDialog
          open={creating}
          onClose={() => setCreating(false)}
          onCreated={(siteId) => {
            setCreating(false)
            setCreated(siteId)
          }}
        />
    </section>
  )
}
