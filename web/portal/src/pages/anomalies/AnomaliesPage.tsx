import {
  Body1,
  Caption1,
  Dropdown,
  Field,
  MessageBar,
  MessageBarBody,
  Option,
  Select,
  Spinner,
  Title2,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAnomalies, useCurrentUser } from '../../api/queries'
import type { Anomaly } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { metricLabel } from '../../components/chartOption'
import { useFeatures } from '../../features'
import { NotFound } from '../NotFound'
import { AnomaliesTable } from './AnomaliesTable'
import { ReviewAnomalyDialog } from './ReviewAnomalyDialog'
import { DEFAULT_STATUSES, STATUSES, STATUS_LABELS, VERDICT_LABELS, readStatuses } from './anomalyText'

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  filters: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalL, alignItems: 'end' },
  status: { minWidth: '18rem' },
})

/** Recent anomalies of the analysed sites (when anomaly detection is enabled). */
export function AnomaliesPage() {
  return useFeatures().anomalies ? <AnomalyList /> : <NotFound />
}

function AnomalyList() {
  const styles = useStyles()
  const sites = useFeatures().anomalySites
  const canWrite = useCurrentUser().data?.canWrite === true
  const [params, setParams] = useSearchParams()
  const siteId = sites.includes(params.get('siteId') ?? '') ? params.get('siteId')! : ''
  const statuses = readStatuses(params.get('status'))
  const anomalies = useAnomalies({ siteId: siteId || undefined, status: statuses })
  const [reviewing, setReviewing] = useState<Anomaly>()
  const [reviewed, setReviewed] = useState<Anomaly>()

  const change = (name: string, value: string) =>
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (value) next.set(name, value)
      else next.delete(name)
      return next
    })

  return (
    <section className={styles.page} aria-labelledby="anomalies-title">
      <Title2 as="h1" id="anomalies-title">Anomalies</Title2>

      <div className={styles.filters} role="search" aria-label="Filter anomalies">
        <Field label="Site">
          <Select value={siteId} onChange={(_, d) => change('siteId', d.value)}>
            <option value="">All analysed sites</option>
            {sites.map((site) => (
              <option key={site} value={site}>
                {site}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Status" className={styles.status}>
          <Dropdown
            multiselect
            selectedOptions={statuses}
            value={statuses.map((s) => STATUS_LABELS[s]).join(', ')}
            onOptionSelect={(_, d) => {
              const next = STATUSES.filter((s) => d.selectedOptions.includes(s))
              if (next.length > 0) {
                change('status', next.join(',') === DEFAULT_STATUSES.join(',') ? '' : next.join(','))
              }
            }}
          >
            {STATUSES.map((s) => (
              <Option key={s} value={s}>
                {STATUS_LABELS[s]}
              </Option>
            ))}
          </Dropdown>
        </Field>
      </div>

      {reviewed && (
        <MessageBar intent="success" role="status">
          <MessageBarBody>
            Review saved: {metricLabel(reviewed.metric, reviewed.unit)} of {reviewed.deviceName ?? reviewed.deviceId},{' '}
            {reviewed.review ? VERDICT_LABELS[reviewed.review.verdict].toLowerCase() : 'reviewed'}.
          </MessageBarBody>
        </MessageBar>
      )}

      <Caption1>Anomalies of the last 7 days, most recent first.{anomalies.isFetching && anomalies.data ? ' Refreshing…' : ''}</Caption1>
      {anomalies.isError && !anomalies.data ? (
        <ErrorState title="The anomalies could not be loaded" error={anomalies.error} onRetry={() => void anomalies.refetch()} />
      ) : !anomalies.data ? (
        <Spinner label="Loading anomalies…" />
      ) : anomalies.data.length === 0 ? (
        <Body1>No anomaly matches these filters in the last 7 days.</Body1>
      ) : (
        <AnomaliesTable anomalies={anomalies.data} label="Anomalies" showDevice onReview={canWrite ? setReviewing : undefined} />
      )}
      {canWrite && <ReviewAnomalyDialog anomaly={reviewing} onClose={() => setReviewing(undefined)} onReviewed={setReviewed} />}
    </section>
  )
}
