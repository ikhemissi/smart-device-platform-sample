import {
  Body1,
  Button,
  Caption1,
  Card,
  Dropdown,
  Field,
  Input,
  Link as FluentLink,
  MessageBar,
  MessageBarBody,
  Option,
  Spinner,
  Subtitle1,
  ToggleButton,
  Toolbar,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import type { UseQueryResult } from '@tanstack/react-query'
import { useState } from 'react'
import type { ReportedMetric } from '../api/types'
import { ErrorState } from '../components/ErrorState'
import { TelemetryChart } from '../components/TelemetryChart'
import { bucketName } from '../components/chartOption'
import { type DevicePageState, MAX_METRICS, PERIODS, RETENTION_MS } from './devicePageState'
import type { DeviceHistory } from './useDeviceHistory'

const useStyles = makeStyles({
  controls: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalL, alignItems: 'end' },
  metrics: { minWidth: '22rem' },
  empty: { padding: tokens.spacingVerticalXXL, textAlign: 'center' },
})

const toLocalInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)

/** Default custom range: the last 24 hours, as datetime-local input values. */
function lastDayInputs(): [string, string] {
  const now = new Date()
  return [toLocalInput(new Date(now.getTime() - 86_400_000)), toLocalInput(now)]
}

interface Props {
  deviceName: string
  history: DeviceHistory
  metrics: UseQueryResult<ReportedMetric[]>
  state: DevicePageState
  /** Changes the chart parameters of the URL (undefined removes one). */
  onChange: (changes: Record<string, string | undefined>) => void
}

/** Chart of up to four metrics over a preset or custom period. */
export function HistoryCard({ deviceName, history: { available, selected, labels, series }, metrics, state, onChange }: Props) {
  const styles = useStyles()
  const [customFrom, setCustomFrom] = useState(() => (state.from ? toLocalInput(state.from) : ''))
  const [customTo, setCustomTo] = useState(() => (state.to ? toLocalInput(state.to) : ''))
  const [customError, setCustomError] = useState<string>()

  const showPreset = (period: string) => onChange({ period, from: undefined, to: undefined })

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
    onChange({ period: 'custom', from: from.toISOString(), to: to.toISOString() })
  }

  const points = series.data?.series.reduce((n, s) => n + s.points.length, 0) ?? 0
  const bucket = series.data ? bucketName(series.data.resolution) : undefined
  const selectedLabels = selected.map((m) => labels[m] ?? m).join(', ')

  return (
    <Card>
      <Subtitle1 as="h2">History</Subtitle1>
      <div className={styles.controls}>
        <Field label="Metrics" hint={`Up to ${MAX_METRICS}`} className={styles.metrics}>
          <Dropdown
            multiselect
            placeholder={metrics.isPending ? 'Loading metrics…' : 'Select metrics'}
            selectedOptions={selected}
            value={selectedLabels}
            onOptionSelect={(_, data) => {
              const next = data.selectedOptions.slice(0, MAX_METRICS)
              if (next.length > 0) onChange({ metrics: next.join(',') })
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
            <ToggleButton key={p.key} checked={state.period === p.key} onClick={() => showPreset(p.key)}>
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
              <FluentLink as="button" onClick={() => showPreset('30d')}>
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
          <TelemetryChart series={series.data} labels={labels} title={`Chart of ${selectedLabels} for ${deviceName}`} />
        </>
      )}
    </Card>
  )
}
