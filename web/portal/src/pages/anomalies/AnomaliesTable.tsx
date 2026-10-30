import {
  Badge,
  Body1,
  Body1Strong,
  Button,
  Caption1,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { Link } from 'react-router'
import type { Anomaly, AnomalyStatus } from '../../api/types'
import { Timestamp } from '../../components/Timestamp'
import { metricLabel } from '../../components/chartOption'
import { formatAbsolute, formatRelative } from '../../format'
import { KIND_LABELS, STATUS_LABELS, VERDICT_LABELS, classificationText, deviceLink, durationText, observedText, usualText } from './anomalyText'

const useStyles = makeStyles({
  scroll: { overflowX: 'auto' },
  cell: { display: 'flex', flexDirection: 'column', paddingTop: tokens.spacingVerticalS, paddingBottom: tokens.spacingVerticalS },
  // Anomalies to review stand out from confirmed ones (with their badge, not by colour alone).
  toReview: { backgroundColor: tokens.colorPaletteYellowBackground1, boxShadow: `inset 4px 0 0 ${tokens.colorPaletteDarkOrangeBorder2}` },
  explanation: { minWidth: '18rem', maxWidth: '32rem' },
  secondary: { color: tokens.colorNeutralForeground3 },
  link: { color: tokens.colorBrandForegroundLink, ':hover': { textDecoration: 'underline' } },
})

const BADGES: Record<AnomalyStatus, { appearance: 'filled' | 'tint' | 'outline'; color: 'brand' | 'warning' | 'success' | 'subtle' | 'informative' }> = {
  confirmed: { appearance: 'filled', color: 'brand' },
  to_review: { appearance: 'filled', color: 'warning' },
  reviewed: { appearance: 'tint', color: 'success' },
  dismissed: { appearance: 'tint', color: 'subtle' },
  pending: { appearance: 'outline', color: 'informative' },
}

export function AnomalyStatusBadge({ status }: { status: AnomalyStatus }) {
  return (
    <Badge shape="rounded" {...BADGES[status]}>
      {STATUS_LABELS[status]}
    </Badge>
  )
}

interface Props {
  anomalies: Anomaly[]
  /** Accessible name of the table. */
  label: string
  /** Device column, linking to the device page over the anomaly's period (the Anomalies page). */
  showDevice?: boolean
  /** Metric names (the device page knows their descriptions). */
  metricName?: (metric: string, unit: string | null) => string
  /** Editors: review action of each classified anomaly. */
  onReview?: (anomaly: Anomaly) => void
}

/** Anomalies, most recent first: values versus usual, classification with its confidence, status, explanation and review. */
export function AnomaliesTable({ anomalies, label, showDevice, metricName = metricLabel, onReview }: Props) {
  const styles = useStyles()
  return (
    <div className={styles.scroll}>
      <Table aria-label={label} size="small">
        <TableHeader>
          <TableRow>
            {showDevice && <TableHeaderCell>Device</TableHeaderCell>}
            <TableHeaderCell>Status</TableHeaderCell>
            <TableHeaderCell>Metric</TableHeaderCell>
            <TableHeaderCell>Period</TableHeaderCell>
            <TableHeaderCell>Values</TableHeaderCell>
            <TableHeaderCell>Classification</TableHeaderCell>
            <TableHeaderCell className={styles.explanation}>Explanation</TableHeaderCell>
            <TableHeaderCell>Review</TableHeaderCell>
            {onReview && <TableHeaderCell><span className="visually-hidden">Actions</span></TableHeaderCell>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {anomalies.map((a) => {
            const metric = metricName(a.metric, a.unit)
            const device = a.deviceName ?? a.deviceId
            const classification = classificationText(a)
            return (
              <TableRow key={a.id} className={a.status === 'to_review' ? styles.toReview : undefined} data-status={a.status}>
                {showDevice && (
                  <TableCell>
                    <div className={styles.cell}>
                      <Link to={deviceLink(a)} className={styles.link}>{device}</Link>
                      <Caption1 className={styles.secondary}>{a.siteId}</Caption1>
                    </div>
                  </TableCell>
                )}
                <TableCell><AnomalyStatusBadge status={a.status} /></TableCell>
                <TableCell>
                  <div className={styles.cell}>
                    <span>{metric}</span>
                    <Caption1 className={styles.secondary}>{KIND_LABELS[a.kind]}</Caption1>
                  </div>
                </TableCell>
                <TableCell>
                  <div className={styles.cell}>
                    <Timestamp value={a.startedAt} />
                    <Caption1>lasted {durationText(a.startedAt, a.endedAt)}</Caption1>
                  </div>
                </TableCell>
                <TableCell>
                  <div className={styles.cell}>
                    <span>{observedText(a)}</span>
                    <Caption1>{usualText(a)}</Caption1>
                  </div>
                </TableCell>
                <TableCell>{classification ?? <Caption1 className={styles.secondary}>Not classified yet</Caption1>}</TableCell>
                <TableCell className={styles.explanation}>
                  <Body1 className={styles.cell}>{a.explanation ?? '–'}</Body1>
                </TableCell>
                <TableCell>
                  {a.review ? (
                    <div className={styles.cell}>
                      <Body1Strong>{VERDICT_LABELS[a.review.verdict]}</Body1Strong>
                      <Caption1 title={formatAbsolute(a.review.reviewedAt)}>
                        by {a.review.reviewedBy}, {formatRelative(a.review.reviewedAt)}
                      </Caption1>
                      {a.review.comment && <Body1>“{a.review.comment}”</Body1>}
                    </div>
                  ) : (
                    '–'
                  )}
                </TableCell>
                {onReview && (
                  <TableCell>
                    {a.status !== 'pending' && (
                      <Button size="small" aria-label={`Review ${metric} anomaly of ${device}`} onClick={() => onReview(a)}>
                        Review
                      </Button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
