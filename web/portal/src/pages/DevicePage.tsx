import { Body1, Caption1, Card, Spinner, Subtitle1, Title2, makeStyles, tokens } from '@fluentui/react-components'
import { ArrowLeftRegular } from '@fluentui/react-icons'
import type { UseQueryResult } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useDevice, useDeviceMetrics, useLatestMeasurements } from '../api/queries'
import type { DeviceDetails, Measurement, ReportedMetric } from '../api/types'
import { ErrorState } from '../components/ErrorState'
import { LatestMeasurementsTable } from '../components/LatestMeasurementsTable'
import { StatusBadge } from '../components/StatusBadge'
import { Timestamp } from '../components/Timestamp'
import { useFeatures } from '../features'
import { withParams } from '../searchParams'
import { AlertsCard, ThresholdsCard } from './alerting/AlertingCards'
import { AnomaliesCard } from './anomalies/AnomaliesCard'
import { HistoryCard } from './HistoryCard'
import { MaintenanceCard } from './maintenance/MaintenanceCard'
import { PERIODS, readState } from './devicePageState'
import { useDeviceHistory } from './useDeviceHistory'

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  header: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: tokens.spacingHorizontalL },
  facts: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalXXL },
  fact: { display: 'flex', flexDirection: 'column' },
  back: { display: 'inline-flex', alignItems: 'center', gap: tokens.spacingHorizontalXS },
})

export function DevicePage() {
  const styles = useStyles()
  const { deviceId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const state = readState(params)
  const preset = PERIODS.find((p) => p.key === state.period)
  const device = useDevice(deviceId)
  const metrics = useDeviceMetrics(deviceId)
  const latest = useLatestMeasurements(deviceId)
  const history = useDeviceHistory(deviceId, state, preset?.ms, metrics.data)
  const features = useFeatures()

  const changeParams = (changes: Record<string, string | undefined>) =>
    setParams((current) => withParams(current, changes), { replace: false })

  if (device.isError && device.error instanceof ApiError && device.error.status === 404) {
    return <DeviceNotFound deviceId={deviceId} />
  }
  const info = device.data?.value
  const reportedMetrics = metrics.data ?? []

  return (
    <section className={styles.page} aria-labelledby="device-title">
      <Link to="/devices" className={styles.back}>
        <ArrowLeftRegular aria-hidden /> Devices
      </Link>
      {device.isError && !info ? (
        <ErrorState title="The device could not be loaded" error={device.error} onRetry={() => void device.refetch()} />
      ) : !info ? (
        <Spinner label="Loading device…" />
      ) : (
        <DeviceSummary device={info} />
      )}

      <HistoryCard
        deviceName={info?.displayName ?? deviceId}
        history={history}
        metrics={metrics}
        state={state}
        onChange={changeParams}
      />
      <LatestMeasurementsCard latest={latest} metrics={reportedMetrics} />

      {features.anomalies && info && (
        <AnomaliesCard
          deviceId={deviceId}
          siteId={info.siteId}
          metrics={reportedMetrics}
          period={state.period}
          presetMs={preset?.ms}
          from={state.from}
          to={state.to}
        />
      )}
      {features.maintenance && info && <MaintenanceCard deviceId={deviceId} />}
      <AlertsCard deviceId={deviceId} metrics={reportedMetrics} />
      <ThresholdsCard deviceId={deviceId} metrics={reportedMetrics} />
    </section>
  )
}

function DeviceNotFound({ deviceId }: { deviceId: string }) {
  const styles = useStyles()
  return (
    <section className={styles.page}>
      <Title2 as="h1">Device not found</Title2>
      <Body1 as="p">
        No device <strong>{deviceId}</strong> exists. <Link to="/devices">Back to the device list</Link>
      </Body1>
    </section>
  )
}

/** Name, status and the facts of the device's catalog entry. */
function DeviceSummary({ device }: { device: DeviceDetails }) {
  const styles = useStyles()
  return (
    <>
      <div className={styles.header}>
        <Title2 as="h1" id="device-title">{device.displayName ?? device.deviceId}</Title2>
        <StatusBadge status={device.status} />
      </div>
      <div className={styles.facts}>
        <Fact label="Device id" value={device.deviceId} />
        <Fact label="Site" value={device.siteId} />
        <Fact label="Format" value={device.lastPayloadFormat} />
        <div className={styles.fact}>
          <Caption1>Last seen</Caption1>
          <Timestamp value={device.lastSeenAt} />
        </div>
        {device.location && <Fact label="Location" value={device.location.label ?? `${device.location.latitude}, ${device.location.longitude}`} />}
      </div>
      {device.description && <Body1 as="p">{device.description}</Body1>}
    </>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  const styles = useStyles()
  return (
    <div className={styles.fact}>
      <Caption1>{label}</Caption1>
      <Body1>{value}</Body1>
    </div>
  )
}

function LatestMeasurementsCard({ latest, metrics }: { latest: UseQueryResult<Measurement[]>; metrics: ReportedMetric[] }) {
  return (
    <Card>
      <Subtitle1 as="h2">Latest measurements</Subtitle1>
      {latest.isError && !latest.data ? (
        <ErrorState title="The latest measurements could not be loaded" error={latest.error} onRetry={() => void latest.refetch()} />
      ) : !latest.data ? (
        <Spinner label="Loading measurements…" />
      ) : latest.data.length === 0 ? (
        <Body1>No measurement yet.</Body1>
      ) : (
        <LatestMeasurementsTable measurements={latest.data} metrics={metrics} />
      )}
    </Card>
  )
}
