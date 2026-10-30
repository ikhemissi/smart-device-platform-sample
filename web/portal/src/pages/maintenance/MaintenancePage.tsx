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
import { useCurrentUser, useMaintenance, useSites } from '../../api/queries'
import type { MaintenanceItem } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { useFeatures } from '../../features'
import { NotFound } from '../NotFound'
import { MaintenanceStatusDialog, type StatusChange } from './MaintenanceStatusDialog'
import { MaintenanceTable } from './MaintenanceTable'
import { DEFAULT_STATUSES, STATUSES, STATUS_LABELS, readStatuses } from './maintenanceText'

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  filters: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalL, alignItems: 'end' },
  status: { minWidth: '18rem' },
})

/** The maintenance queue of the assessed sites (when predictive maintenance is enabled). */
export function MaintenancePage() {
  return useFeatures().maintenance ? <MaintenanceQueue /> : <NotFound />
}

function MaintenanceQueue() {
  const styles = useStyles()
  const sites = useSites()
  const canWrite = useCurrentUser().data?.canWrite === true
  const [params, setParams] = useSearchParams()
  const siteId = params.get('siteId') ?? ''
  const statuses = readStatuses(params.get('status'))
  const items = useMaintenance({ siteId: siteId || undefined, status: statuses })
  const [changing, setChanging] = useState<StatusChange>()
  const [changed, setChanged] = useState<MaintenanceItem>()

  const change = (name: string, value: string) =>
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (value) next.set(name, value)
      else next.delete(name)
      return next
    })

  return (
    <section className={styles.page} aria-labelledby="maintenance-title">
      <Title2 as="h1" id="maintenance-title">Maintenance</Title2>

      <div className={styles.filters} role="search" aria-label="Filter the maintenance queue">
        <Field label="Site">
          <Select value={siteId} onChange={(_, d) => change('siteId', d.value)}>
            <option value="">All sites</option>
            {(sites.data ?? []).map((site) => (
              <option key={site.siteId} value={site.siteId}>
                {site.displayName}
              </option>
            ))}
            {siteId && sites.data && !sites.data.some((s) => s.siteId === siteId) && <option value={siteId}>{siteId}</option>}
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

      {changed && (
        <MessageBar intent="success" role="status">
          <MessageBarBody>
            {changed.deviceName ?? changed.deviceId}: visit {STATUS_LABELS[changed.status].toLowerCase()}.
          </MessageBarBody>
        </MessageBar>
      )}

      <Caption1>
        Devices likely to need an on-site intervention within 7 days, most likely first.
        {items.isFetching && items.data ? ' Refreshing…' : ''}
      </Caption1>
      {items.isError && !items.data ? (
        <ErrorState title="The maintenance queue could not be loaded" error={items.error} onRetry={() => void items.refetch()} />
      ) : !items.data ? (
        <Spinner label="Loading the maintenance queue…" />
      ) : items.data.length === 0 ? (
        <Body1>No device matches these filters.</Body1>
      ) : (
        <MaintenanceTable
          items={items.data}
          label="Maintenance queue"
          onStatus={canWrite ? (item, status) => setChanging({ item, status }) : undefined}
        />
      )}
      {canWrite && <MaintenanceStatusDialog change={changing} onClose={() => setChanging(undefined)} onChanged={setChanged} />}
    </section>
  )
}
