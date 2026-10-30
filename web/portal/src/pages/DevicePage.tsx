import {
  Body1,
  Button,
  Caption1,
  Card,
  Field,
  Input,
  Link as FluentLink,
  Option,
  Dropdown,
  MessageBar,
  MessageBarBody,
  Spinner,
  Subtitle1,
  Title2,
  ToggleButton,
  Toolbar,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { ArrowLeftRegular } from '@fluentui/react-icons'
import { useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useDevice, useDeviceMetrics, useLatestMeasurements, useSeries } from '../api/queries'
import { ErrorState } from '../components/ErrorState'
import { LatestMeasurementsTable } from '../components/LatestMeasurementsTable'
import { StatusBadge } from '../components/StatusBadge'
import { TelemetryChart } from '../components/TelemetryChart'
import { Timestamp } from '../components/Timestamp'
import { bucketName, metricLabel } from '../components/chartOption'
import { useFeatures } from '../features'
import { AlertsCard, ThresholdsCard } from './alerting/AlertingCards'
import { AnomaliesCard } from './anomalies/AnomaliesCard'
import { MaintenanceCard } from './maintenance/MaintenanceCard'
import { MAX_METRICS, PERIODS, RETENTION_MS, defaultMetrics, readState } from './devicePageState'

/** Points requested for a chart: 1 h → raw, 24 h → 5-minute, 7 d → 15-minute, 30 d → 1-hour buckets at 30 s reporting. */
export const MAX_POINTS = 800

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  header: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: tokens.spacingHorizontalL },
  facts: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalXXL },
  fact: { display: 'flex', flexDirection: 'column' },
  controls: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalL, alignItems: 'end' },
  metrics: { minWidth: '22rem' },
  empty: { padding: tokens.spacingVerticalXXL, textAlign: 'center' },
  back: { display: 'inline-flex', alignItems: 'center', gap: tokens.spacingHorizontalXS },
})

const toLocalInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)

/** Default custom range: the last 24 hours, as datetime-local input values. */
function lastDayInputs(): [string, string] {
  const now = new Date()
  return [toLocalInput(new Date(now.getTime() - 86_400_000)), toLocalInput(now)]
}

export function DevicePage() {
  const styles = useStyles()
  const { deviceId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const state = readState(params)
  const device = useDevice(deviceId)
  const metrics = useDeviceMetrics(deviceId)
  const latest = useLatestMeasurements(deviceId)
  const features = useFeatures()

  const available = useMemo(() => metrics.data?.map((m) => m.metric) ?? [], [metrics.data])
  const selected = (state.metrics ?? defaultMetrics(available)).filter((m) => metrics.data === undefined || available.includes(m))
  const labels = useMemo(
    () => Object.fromEntries((metrics.data ?? []).map((m) => [m.metric, metricLabel(m.metric, m.unit, m.description)])),
    [metrics.data],
  )
  const preset = PERIODS.find((p) => p.key === state.period)
  const series = useSeries({
    deviceId,
    metrics: selected,
    period: state.period,
    presetMs: preset?.ms,
    from: state.from,
    to: state.to,
    maxPoints: MAX_POINTS,
  })

  const update = (changes: Record<string, string | undefined>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [k, v] of Object.entries(changes)) {
          if (v === undefined || v === '') next.delete(k)
          else next.set(k, v)
        }
        return next
      },
      { replace: false },
    )

  const [customFrom, setCustomFrom] = useState(() => (state.from ? toLocalInput(state.from) : ''))
  const [customTo, setCustomTo] = useState(() => (state.to ? toLocalInput(state.to) : ''))
  const [customError, setCustomError] = useState<string>()

  const showCustom = () => {
    const [lastDayFrom, lastDayTo] = lastDayInputs()
    const from = customFrom || lastDayFrom
    const to = customTo || lastDayTo
    setCustomFrom(from)
    setCustomTo(to)
    applyCustom(from, to)
  }

  const applyCustom = (fromInput: string, toInput: string) => {
    const from = new Date(fromInput)
    const to = new Date(toInput)
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || from >= to || from.getTime() < Date.now() - RETENTION_MS) {
      setCustomError('Choose a range within the last 30 days, ending after its start.')
      return
    }
    setCustomError(undefined)
    update({ period: 'custom', from: from.toISOString(), to: to.toISOString() })
  }

  if (device.isError && device.error instanceof ApiError && device.error.status === 404) {
    return (
      <section className={styles.page}>
        <Title2 as="h1">Device not found</Title2>
        <Body1 as="p">
          No device <strong>{deviceId}</strong> exists. <Link to="/devices">Back to the device list</Link>
        </Body1>
      </section>
    )
  }
  const info = device.data?.value
  const points = series.data?.series.reduce((n, s) => n + s.points.length, 0) ?? 0
  const bucket = series.data ? bucketName(series.data.resolution) : undefined

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
        <>
          <div className={styles.header}>
            <Title2 as="h1" id="device-title">{info.displayName ?? info.deviceId}</Title2>
            <StatusBadge status={info.status} />
          </div>
          <div className={styles.facts}>
            <Fact label="Device id" value={info.deviceId} />
            <Fact label="Site" value={info.siteId} />
            <Fact label="Format" value={info.lastPayloadFormat} />
            <div className={styles.fact}>
              <Caption1>Last seen</Caption1>
              <Timestamp value={info.lastSeenAt} />
            </div>
            {info.location && <Fact label="Location" value={info.location.label ?? `${info.location.latitude}, ${info.location.longitude}`} />}
          </div>
          {info.description && <Body1 as="p">{info.description}</Body1>}
        </>
      )}

      <Card>
        <Subtitle1 as="h2">History</Subtitle1>
        <div className={styles.controls}>
          <Field label="Metrics" hint={`Up to ${MAX_METRICS}`} className={styles.metrics}>
            <Dropdown
              multiselect
              placeholder={metrics.isPending ? 'Loading metrics…' : 'Select metrics'}
              selectedOptions={selected}
              value={selected.map((m) => labels[m] ?? m).join(', ')}
              onOptionSelect={(_, data) => {
                const next = data.selectedOptions.slice(0, MAX_METRICS)
                if (next.length > 0) update({ metrics: next.join(',') })
              }}
            >
              {(metrics.data ?? []).map((m) => (
                <Option
                  key={m.metric}
                  value={m.metric}
                  text={labels[m.metric]}
                  disabled={!selected.includes(m.metric) && selected.length >= MAX_METRICS}
                >
                  {labels[m.metric]}
                </Option>
              ))}
            </Dropdown>
          </Field>
          <Toolbar aria-label="Period">
            {PERIODS.map((p) => (
              <ToggleButton key={p.key} checked={state.period === p.key} onClick={() => update({ period: p.key, from: undefined, to: undefined })}>
                {p.label}
              </ToggleButton>
            ))}
            <ToggleButton checked={state.period === 'custom'} onClick={showCustom}>
              Custom
            </ToggleButton>
          </Toolbar>
        </div>
        {state.period === 'custom' && (
          <div className={styles.controls}>
            <Field label="From">
              <Input type="datetime-local" value={customFrom} onChange={(_, d) => setCustomFrom(d.value)} />
            </Field>
            <Field label="To" validationMessage={customError}>
              <Input type="datetime-local" value={customTo} onChange={(_, d) => setCustomTo(d.value)} />
            </Field>
            <Button onClick={() => applyCustom(customFrom, customTo)}>
              Apply
            </Button>
          </div>
        )}

        {series.isError && !series.data ? (
          <ErrorState title="The history could not be loaded" error={series.error} onRetry={() => void series.refetch()} />
        ) : metrics.isSuccess && available.length === 0 ? (
          <div className={styles.empty}><Body1>This device has not reported any measurement in the last 30 days.</Body1></div>
        ) : !series.data ? (
          <Spinner label="Loading history…" />
        ) : points === 0 ? (
          <MessageBar intent="info" className={styles.empty}>
            <MessageBarBody>
              No data exists for this period.{' '}
              {state.period !== '30d' && (
                <FluentLink as="button" onClick={() => update({ period: '30d', from: undefined, to: undefined })}>
                  Try a longer period
                </FluentLink>
              )}
            </MessageBarBody>
          </MessageBar>
        ) : (
          <>
            <Caption1 aria-live="polite">
              {bucket ? `${bucket} averages with minimum–maximum range` : 'Every measurement'}
              {series.isFetching ? ' · refreshing…' : ''}
            </Caption1>
            <TelemetryChart
              series={series.data}
              labels={labels}
              title={`Chart of ${selected.map((m) => labels[m] ?? m).join(', ')} for ${info?.displayName ?? deviceId}`}
            />
          </>
        )}
      </Card>

      <Card>
        <Subtitle1 as="h2">Latest measurements</Subtitle1>
        {latest.isError && !latest.data ? (
          <ErrorState title="The latest measurements could not be loaded" error={latest.error} onRetry={() => void latest.refetch()} />
        ) : !latest.data ? (
          <Spinner label="Loading measurements…" />
        ) : latest.data.length === 0 ? (
          <Body1>No measurement yet.</Body1>
        ) : (
          <LatestMeasurementsTable measurements={latest.data} metrics={metrics.data ?? []} />
        )}
      </Card>

      {features.anomalies && info && (
        <AnomaliesCard
          deviceId={deviceId}
          siteId={info.siteId}
          metrics={metrics.data ?? []}
          period={state.period}
          presetMs={preset?.ms}
          from={state.from}
          to={state.to}
        />
      )}
      {features.maintenance && info && <MaintenanceCard deviceId={deviceId} />}
      <AlertsCard deviceId={deviceId} metrics={metrics.data ?? []} />
      <ThresholdsCard deviceId={deviceId} metrics={metrics.data ?? []} />
    </section>
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
