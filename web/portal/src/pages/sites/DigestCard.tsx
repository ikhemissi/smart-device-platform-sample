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
  Subtitle2,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { ArrowClockwiseRegular } from '@fluentui/react-icons'
import { Fragment, type ReactNode, useId } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useRequestSiteDigest, useSiteDigest } from '../../api/queries'
import type { Digest, DigestFigures } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { formatAbsolute, formatRelative } from '../../format'
import { CLASSIFICATION_LABELS, KIND_LABELS, STATUS_LABELS } from '../anomalies/anomalyText'
import { briefParts, countsText, days, figureDeviceIds, formatChange, formatNumber } from './digestText'

const useStyles = makeStyles({
  card: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
  header: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: tokens.spacingHorizontalM },
  brief: { margin: 0, maxWidth: '60rem' },
  sections: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(18rem, 1fr))',
    rowGap: tokens.spacingVerticalL,
    columnGap: tokens.spacingHorizontalXL,
  },
  wide: { gridColumn: '1 / -1', overflowX: 'auto' },
  section: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalXS },
  list: { margin: 0, paddingLeft: tokens.spacingHorizontalL, display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalXXS },
  link: { color: tokens.colorBrandForegroundLink },
})

/** The latest digest of a site: brief, period, figures; regeneration for Editors (design D7). */
export function DigestCard({ siteId, canWrite }: { siteId: string; canWrite: boolean }) {
  const styles = useStyles()
  const digest = useSiteDigest(siteId)
  const regenerate = useRequestSiteDigest(siteId)
  const data = digest.data
  const pending = data?.pendingRequest

  return (
    <Card className={styles.card}>
      <div className={styles.header}>
        <Subtitle1 as="h2">Digest</Subtitle1>
        {canWrite && data?.enabled && (
          <Button icon={<ArrowClockwiseRegular />} disabled={Boolean(pending) || regenerate.isPending} onClick={() => regenerate.mutate()}>
            Regenerate
          </Button>
        )}
      </div>
      {regenerate.isError && (
        <MessageBar intent="error" layout="multiline" role="alert">
          <MessageBarBody>
            <MessageBarTitle>A new digest could not be requested</MessageBarTitle>
            {requestFailureText(regenerate.error)}
          </MessageBarBody>
        </MessageBar>
      )}
      {pending && (
        <MessageBar intent="info" layout="multiline" role="status">
          <MessageBarBody>
            A new digest is being generated (requested by {pending.requestedBy} at <time dateTime={pending.requestedAt}>{formatAbsolute(pending.requestedAt)}</time>).
            It appears here within minutes.
          </MessageBarBody>
        </MessageBar>
      )}
      {digest.isError && !data ? (
        <ErrorState title="The digest could not be loaded" error={digest.error} onRetry={() => void digest.refetch()} />
      ) : !data ? (
        <Spinner size="tiny" label="Loading the digest…" />
      ) : !data.enabled ? (
        <Body1>This site has no digest: digests are not generated for it in this environment.</Body1>
      ) : !data.digest ? (
        <Body1>No digest yet: the first one is generated within the day.</Body1>
      ) : (
        <DigestView digest={data.digest} />
      )}
    </Card>
  )
}

/** The API's explanation when the site gets no digests (409), or a generic one. */
function requestFailureText(error: unknown): string {
  if (error instanceof ApiError && error.status === 409 && error.problem.detail) {
    return error.problem.detail
  }
  return explain(error)
}

function DigestView({ digest }: { digest: Digest }) {
  const styles = useStyles()
  const figures = digest.figures ?? {}
  const deviceIds = figureDeviceIds(figures)
  return (
    <>
      <Caption1>
        Period <Instant value={digest.periodFrom} /> – <Instant value={digest.periodTo} /> · generated{' '}
        <RelativeInstant value={digest.generatedAt} />
        {digest.trigger === 'requested' ? ` on request${digest.requestedBy ? ` of ${digest.requestedBy}` : ''}` : ''}
      </Caption1>
      {digest.brief ? (
        <Body1 as="p" className={styles.brief}>
          {briefParts(digest.brief, deviceIds).map((part, i) =>
            'deviceId' in part ? <DeviceLink key={i} deviceId={part.deviceId} /> : <Fragment key={i}>{part.text}</Fragment>,
          )}
        </Body1>
      ) : (
        <MessageBar intent="warning" layout="multiline">
          <MessageBarBody>
            <MessageBarTitle>Brief unavailable</MessageBarTitle>
            The language model could not write the brief of this digest; a later run adds it. The figures below are complete.
          </MessageBarBody>
        </MessageBar>
      )}
      <Figures figures={figures} comparisonDays={days(digest.comparisonFrom, digest.periodFrom)} />
    </>
  )
}

function Instant({ value }: { value: string }) {
  return <time dateTime={value}>{formatAbsolute(value)}</time>
}

function RelativeInstant({ value }: { value: string }) {
  return (
    <time dateTime={value} title={formatAbsolute(value)}>
      {formatRelative(value)}
    </time>
  )
}

function DeviceLink({ deviceId }: { deviceId: string }) {
  const styles = useStyles()
  return (
    <Link to={`/devices/${encodeURIComponent(deviceId)}`} className={styles.link}>
      {deviceId}
    </Link>
  )
}

function Section({ title, wide, children }: { title: string; wide?: boolean; children: ReactNode }) {
  const styles = useStyles()
  const id = useId()
  return (
    <section className={`${styles.section} ${wide ? styles.wide : ''}`} aria-labelledby={id}>
      <Subtitle2 as="h3" id={id}>
        {title}
      </Subtitle2>
      {children}
    </section>
  )
}

function More({ total, shown }: { total: number; shown: number }) {
  return total > shown ? <Caption1>and {total - shown} more</Caption1> : null
}

type Figure<K extends keyof DigestFigures> = NonNullable<DigestFigures[K]>

function Figures({ figures, comparisonDays }: { figures: DigestFigures; comparisonDays: number }) {
  const styles = useStyles()
  const { devices, metrics, alerts, anomalies, rejectedMessages, certificates } = figures
  return (
    <div className={styles.sections}>
      {metrics && <MetricsSection metrics={metrics} comparisonDays={comparisonDays} />}
      {devices && <DevicesSection devices={devices} />}
      {alerts && <AlertsSection alerts={alerts} metrics={metrics} />}
      {anomalies && <AnomaliesSection anomalies={anomalies} />}
      {rejectedMessages && <RejectedMessagesSection rejectedMessages={rejectedMessages} />}
      {certificates && <CertificatesSection certificates={certificates} />}
    </div>
  )
}

function MetricsSection({ metrics, comparisonDays }: { metrics: Figure<'metrics'>; comparisonDays: number }) {
  return (
    <Section title="Metrics" wide>
      {metrics.length === 0 ? (
        <Body1>No measurement in the period.</Body1>
      ) : (
        <Table aria-label="Metrics" size="small">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>Metric</TableHeaderCell>
              <TableHeaderCell>Unit</TableHeaderCell>
              <TableHeaderCell>Average</TableHeaderCell>
              <TableHeaderCell>Previous {comparisonDays} days</TableHeaderCell>
              <TableHeaderCell>Change</TableHeaderCell>
              <TableHeaderCell>Change %</TableHeaderCell>
            </TableRow>
          </TableHeader>
          <TableBody>
            {metrics.map((m) => (
              <TableRow key={m.metric}>
                <TableCell>{m.metric}</TableCell>
                <TableCell>{m.unit || '–'}</TableCell>
                <TableCell>{formatNumber(m.average)}</TableCell>
                <TableCell>{formatNumber(m.comparisonAverage)}</TableCell>
                <TableCell>{formatChange(m.change)}</TableCell>
                <TableCell>{formatChange(m.changePercent, ' %')}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}

function DevicesSection({ devices }: { devices: Figure<'devices'> }) {
  const styles = useStyles()
  const { silent } = devices
  return (
    <Section title="Devices">
      <Body1>
        {devices.reporting} of {devices.total} reporting
        {silent.total > 0 ? `, ${silent.total} silent:` : ', none silent.'}
      </Body1>
      {silent.items.length > 0 && (
        <ul className={styles.list} aria-label="Silent devices">
          {silent.items.map((d) => (
            <li key={d.deviceId}>
              <DeviceLink deviceId={d.deviceId} />
              {d.name ? ` (${d.name})` : ''}, last seen {d.lastSeenAt ? <RelativeInstant value={d.lastSeenAt} /> : 'never'}
            </li>
          ))}
        </ul>
      )}
      <More total={silent.total} shown={silent.items.length} />
    </Section>
  )
}

function AlertsSection({ alerts, metrics }: { alerts: Figure<'alerts'>; metrics: DigestFigures['metrics'] }) {
  const styles = useStyles()
  const withUnit = (value: number, metric: string) => {
    const unit = metrics?.find((m) => m.metric === metric)?.unit ?? ''
    return unit ? `${formatNumber(value)} ${unit}` : formatNumber(value)
  }
  return (
    <Section title="Alerts">
      <Body1>
        {alerts.openNow} open now, {alerts.total} in the period{alerts.items.length > 0 ? ':' : '.'}
      </Body1>
      {alerts.items.length > 0 && (
        <ul className={styles.list} aria-label="Alerts of the period">
          {alerts.items.map((a, i) => (
            <li key={i}>
              <DeviceLink deviceId={a.deviceId} />: {a.metric} {a.direction} threshold, worst {withUnit(a.worstValue, a.metric)}, {a.status}{' '}
              (opened <Instant value={a.openedAt} />)
            </li>
          ))}
        </ul>
      )}
      <More total={alerts.total} shown={alerts.items.length} />
    </Section>
  )
}

function AnomaliesSection({ anomalies }: { anomalies: Figure<'anomalies'> }) {
  const styles = useStyles()
  return (
    <Section title="Anomalies">
      <Body1>{anomalies.total === 0 ? 'None.' : `${anomalies.total} in the period.`}</Body1>
      {anomalies.total > 0 && (
        <>
          <Body1>By status: {countsText(anomalies.byStatus, STATUS_LABELS) || '–'}</Body1>
          <Body1>By classification: {countsText(anomalies.byClassification, CLASSIFICATION_LABELS) || '–'}</Body1>
        </>
      )}
      {anomalies.items.length > 0 && (
        <ul className={styles.list} aria-label="Anomalies of the period">
          {anomalies.items.map((a, i) => (
            <li key={i}>
              <DeviceLink deviceId={a.deviceId} />: {a.metric}, {(KIND_LABELS[a.kind] ?? a.kind).toLowerCase()},{' '}
              {a.classification ? `${CLASSIFICATION_LABELS[a.classification] ?? a.classification}${a.confidencePercent !== null ? ` ${a.confidencePercent} %` : ''}, ` : ''}
              {(STATUS_LABELS[a.status] ?? a.status).toLowerCase()} (<Instant value={a.startedAt} />)
            </li>
          ))}
        </ul>
      )}
      <More total={anomalies.total} shown={anomalies.items.length} />
    </Section>
  )
}

function RejectedMessagesSection({ rejectedMessages }: { rejectedMessages: Figure<'rejectedMessages'> }) {
  return (
    <Section title="Rejected messages">
      <Body1>
        {rejectedMessages.total === 0 ? 'None.' : `${formatNumber(rejectedMessages.total)}: ${countsText(rejectedMessages.byReason)}`}
      </Body1>
    </Section>
  )
}

function CertificatesSection({ certificates }: { certificates: Figure<'certificates'> }) {
  const styles = useStyles()
  return (
    <Section title="Certificates expiring within 30 days">
      {certificates.expiringWithin30Days.length === 0 ? (
        <Body1>None.</Body1>
      ) : (
        <ul className={styles.list} aria-label="Certificates expiring within 30 days">
          {certificates.expiringWithin30Days.map((c) => (
            <li key={`${c.slot}-${c.subject}`}>
              {c.subject} ({c.slot}): {c.daysLeft} day(s) left, until <Instant value={c.notAfter} />
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}
