import {
  Badge,
  Body1,
  Button,
  Caption1,
  Card,
  Spinner,
  Subtitle1,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { AddRegular, DeleteRegular, EditRegular } from '@fluentui/react-icons'
import { useState } from 'react'
import { useCanWrite, useDeviceAlerts, useThresholds } from '../../api/queries'
import type { Alert, ReportedMetric, Threshold } from '../../api/types'
import { Timestamp } from '../../components/Timestamp'
import { metricLabeler } from '../../metricLabel'
import { DeleteThresholdDialog } from './DeleteThresholdDialog'
import { ThresholdDialog } from './ThresholdDialog'
import { alertSummary, notificationSummary, quantity, rangeText } from './alertText'

/** Thresholds per device accepted by the API. */
const MAX_THRESHOLDS = 20

const useStyles = makeStyles({
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: tokens.spacingHorizontalM },
  openRow: { backgroundColor: tokens.colorPaletteRedBackground1 },
  failed: { color: tokens.colorPaletteRedForeground1 },
})

/** Thresholds of a device: visible to everyone; create, edit, enable/disable and delete for Editors. */
export function ThresholdsCard({ deviceId, metrics }: { deviceId: string; metrics: ReportedMetric[] }) {
  const styles = useStyles()
  const canWrite = useCanWrite()
  const thresholds = useThresholds(deviceId)
  const label = metricLabeler(metrics)
  const [editing, setEditing] = useState<{ threshold?: Threshold } | undefined>()
  const [deleting, setDeleting] = useState<Threshold>()

  return (
    <Card>
      <div className={styles.header}>
        <Subtitle1 as="h2">Thresholds</Subtitle1>
        {canWrite && (
          <Button icon={<AddRegular />} onClick={() => setEditing({})} disabled={metrics.length === 0 || (thresholds.data?.length ?? 0) >= MAX_THRESHOLDS}>
            Add threshold
          </Button>
        )}
      </div>
      {!thresholds.data ? (
        thresholds.isError ? <Body1>The thresholds could not be loaded.</Body1> : <Spinner size="tiny" label="Loading…" />
      ) : thresholds.data.length === 0 ? (
        <Body1>No threshold: this device never raises alerts.{canWrite ? ' Add one to be emailed when a reading leaves its expected range.' : ''}</Body1>
      ) : (
        <Table aria-label="Thresholds" size="small">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>Metric</TableHeaderCell>
              <TableHeaderCell>Expected range</TableHeaderCell>
              <TableHeaderCell>Recipients</TableHeaderCell>
              <TableHeaderCell>Status</TableHeaderCell>
              {canWrite && <TableHeaderCell><span className="visually-hidden">Actions</span></TableHeaderCell>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {thresholds.data.map((t) => (
              <TableRow key={t.id}>
                <TableCell>{label(t.metric, t.unit)}</TableCell>
                <TableCell>{rangeText(t.min, t.max, t.unit)}</TableCell>
                <TableCell>{t.recipients.join(', ')}</TableCell>
                <TableCell><ThresholdStatusBadge threshold={t} /></TableCell>
                {canWrite && (
                  <TableCell>
                    <Button appearance="subtle" icon={<EditRegular />} aria-label={`Edit threshold on ${label(t.metric)}`} onClick={() => setEditing({ threshold: t })} />
                    <Button appearance="subtle" icon={<DeleteRegular />} aria-label={`Delete threshold on ${label(t.metric)}`} onClick={() => setDeleting(t)} />
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {canWrite && (
        <>
          <ThresholdDialog deviceId={deviceId} metrics={metrics} threshold={editing?.threshold} open={!!editing} onClose={() => setEditing(undefined)} />
          <DeleteThresholdDialog deviceId={deviceId} threshold={deleting} label={deleting ? label(deleting.metric) : ''} onClose={() => setDeleting(undefined)} />
        </>
      )}
    </Card>
  )
}

function ThresholdStatusBadge({ threshold }: { threshold: Threshold }) {
  if (!threshold.enabled) return <Badge appearance="tint" color="subtle" shape="rounded">Disabled</Badge>
  if (threshold.openAlertId) return <Badge appearance="filled" color="danger" shape="rounded">Alerting</Badge>
  return <Badge appearance="tint" color="success" shape="rounded">Watching</Badge>
}

function AlertStatusBadge({ alert }: { alert: Alert }) {
  if (alert.status === 'OPEN') return <Badge appearance="filled" color="danger" shape="rounded">Open</Badge>
  if (alert.status === 'CLOSED') return <Badge appearance="tint" color="success" shape="rounded">Back to normal</Badge>
  return <Badge appearance="tint" color="subtle" shape="rounded">Cancelled</Badge>
}

/** Alert history of a device, most recent first; open alerts highlighted. */
export function AlertsCard({ deviceId, metrics }: { deviceId: string; metrics: ReportedMetric[] }) {
  const styles = useStyles()
  const alerts = useDeviceAlerts(deviceId)
  const label = metricLabeler(metrics)

  return (
    <Card>
      <Subtitle1 as="h2">Alerts</Subtitle1>
      {!alerts.data ? (
        alerts.isError ? <Body1>The alerts could not be loaded.</Body1> : <Spinner size="tiny" label="Loading…" />
      ) : alerts.data.length === 0 ? (
        <Body1>No alert in the last 30 days.</Body1>
      ) : (
        <Table aria-label="Alerts" size="small">
          <TableHeader>
            <TableRow>
              <TableHeaderCell>Status</TableHeaderCell>
              <TableHeaderCell>Metric</TableHeaderCell>
              <TableHeaderCell>Breach</TableHeaderCell>
              <TableHeaderCell>Opened</TableHeaderCell>
              <TableHeaderCell>Closed</TableHeaderCell>
              <TableHeaderCell>Emails</TableHeaderCell>
            </TableRow>
          </TableHeader>
          <TableBody>
            {alerts.data.map((a) => {
              const emails = notificationSummary(a.notifications)
              return (
                <TableRow key={a.id} className={a.status === 'OPEN' ? styles.openRow : undefined} data-status={a.status}>
                  <TableCell><AlertStatusBadge alert={a} /></TableCell>
                  <TableCell>{label(a.metric, a.unit)}</TableCell>
                  <TableCell>
                    {alertSummary(a)}
                    <br />
                    <Caption1>last {quantity(a.lastValue, a.unit)}</Caption1>
                  </TableCell>
                  <TableCell><Timestamp value={a.openedAt} /></TableCell>
                  <TableCell>{a.closedAt ? <Timestamp value={a.closedAt} /> : '–'}</TableCell>
                  <TableCell className={emails.failed ? styles.failed : undefined}>{emails.text}</TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Card>
  )
}
