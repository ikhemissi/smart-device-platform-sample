import { Body1, Card, Spinner, Subtitle1, Switch, makeStyles, tokens } from '@fluentui/react-components'
import { useState } from 'react'
import { useCurrentUser, useDeviceAnomalies } from '../../api/queries'
import type { Anomaly, ReportedMetric } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { metricLabel } from '../../components/chartOption'
import { useFeatures } from '../../features'
import { AnomaliesTable } from './AnomaliesTable'
import { ReviewAnomalyDialog } from './ReviewAnomalyDialog'
import { DEVICE_STATUSES, DEVICE_STATUSES_WITH_DISMISSED } from './anomalyText'

const useStyles = makeStyles({
  header: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: tokens.spacingHorizontalM },
})

interface Props {
  deviceId: string
  siteId: string
  metrics: ReportedMetric[]
  /** The device page's period: a preset key with its length, or `custom` with its bounds. */
  period: string
  presetMs?: number
  from?: Date
  to?: Date
}

/** Anomalies of a device over the page's period, most recent first; dismissed ones on request; review for Editors. */
export function AnomaliesCard(props: Props) {
  const styles = useStyles()
  const analysed = useFeatures().anomalySites.includes(props.siteId)
  const [showDismissed, setShowDismissed] = useState(false)

  return (
    <Card>
      <div className={styles.header}>
        <Subtitle1 as="h2">Anomalies</Subtitle1>
        {analysed && <Switch label="Show dismissed" checked={showDismissed} onChange={(_, d) => setShowDismissed(d.checked)} />}
      </div>
      {analysed ? <DeviceAnomalies {...props} showDismissed={showDismissed} /> : <Body1>Anomalies are not analysed for this site.</Body1>}
    </Card>
  )
}

function DeviceAnomalies({ deviceId, metrics, period, presetMs, from, to, showDismissed }: Props & { showDismissed: boolean }) {
  const canWrite = useCurrentUser().data?.canWrite === true
  const anomalies = useDeviceAnomalies({
    deviceId,
    status: showDismissed ? DEVICE_STATUSES_WITH_DISMISSED : DEVICE_STATUSES,
    period,
    presetMs,
    from,
    to,
  })
  const [reviewing, setReviewing] = useState<Anomaly>()
  const metricName = (metric: string, unit: string | null) => {
    const m = metrics.find((r) => r.metric === metric)
    return metricLabel(metric, unit ?? m?.unit, m?.description)
  }

  if (anomalies.isError && !anomalies.data) {
    return <ErrorState title="The anomalies could not be loaded" error={anomalies.error} onRetry={() => void anomalies.refetch()} />
  }
  if (!anomalies.data) {
    return <Spinner size="tiny" label="Loading anomalies…" />
  }
  return (
    <>
      {anomalies.data.length === 0 ? (
        <Body1>No anomaly in this period.</Body1>
      ) : (
        <AnomaliesTable anomalies={anomalies.data} label="Anomalies" metricName={metricName} onReview={canWrite ? setReviewing : undefined} />
      )}
      {canWrite && <ReviewAnomalyDialog anomaly={reviewing} metricName={metricName} onClose={() => setReviewing(undefined)} />}
    </>
  )
}
