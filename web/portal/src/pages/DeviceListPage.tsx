import {
  Body1,
  Button,
  Caption1,
  Field,
  SearchBox,
  Select,
  Spinner,
  Table,
  TableBody,
  TableCell,
  TableCellLayout,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Title2,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { ChevronLeftRegular, ChevronRightRegular, EditRegular } from '@fluentui/react-icons'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useCurrentUser, useDevices, useSites } from '../api/queries'
import type { DeviceSummary, SortField } from '../api/types'
import { ErrorState } from '../components/ErrorState'
import { StatusBadge } from '../components/StatusBadge'
import { Timestamp } from '../components/Timestamp'
import { numberFormat } from '../format'
import { useDebouncedValue } from '../useDebouncedValue'
import { EditDeviceDialog } from './EditDeviceDialog'
import { nextOrder, readDeviceQuery, updateParams } from './deviceQuery'

export const SEARCH_DEBOUNCE_MS = 300

const useStyles = makeStyles({
  page: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  filters: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalL, alignItems: 'end' },
  search: { minWidth: '18rem' },
  pager: { display: 'flex', alignItems: 'center', gap: tokens.spacingHorizontalM, justifyContent: 'flex-end' },
  empty: { padding: tokens.spacingVerticalXXL, textAlign: 'center' },
  secondary: { color: tokens.colorNeutralForeground3 },
})

const COLUMNS: { label: string; sort?: SortField }[] = [
  { label: 'Device', sort: 'displayName' },
  { label: 'Site', sort: 'siteId' },
  { label: 'Format' },
  { label: 'First seen', sort: 'firstSeen' },
  { label: 'Last seen', sort: 'lastSeen' },
  { label: 'Status' },
]

export function DeviceListPage() {
  const styles = useStyles()
  const [params, setParams] = useSearchParams()
  const query = readDeviceQuery(params)
  const me = useCurrentUser()
  const canWrite = me.data?.canWrite === true
  const sites = useSites()
  const devices = useDevices(query)
  const [editing, setEditing] = useState<string>()

  // Search: typed text is debounced into the URL; URL changes (back/forward, clear) are reflected in the box.
  const urlSearch = params.get('search') ?? ''
  const [searchText, setSearchText] = useState(urlSearch)
  const debouncedSearch = useDebouncedValue(searchText, SEARCH_DEBOUNCE_MS)
  useEffect(() => {
    if (debouncedSearch.trim() !== urlSearch) {
      setParams((current) => updateParams(current, { search: debouncedSearch.trim() }), { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only react to the debounced text
  }, [debouncedSearch])
  useEffect(() => {
    if (urlSearch !== debouncedSearch.trim()) {
      setSearchText(urlSearch)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only react to URL changes
  }, [urlSearch])

  const change = (changes: Parameters<typeof updateParams>[1]) => setParams((current) => updateParams(current, changes))
  const hasFilters = !!(query.siteId || query.status || query.search)
  const data = devices.data
  const pageCount = data ? Math.max(1, Math.ceil(data.total / query.pageSize)) : 1

  return (
    <section className={styles.page} aria-labelledby="devices-title">
      <Title2 as="h1" id="devices-title">Devices</Title2>

      <div className={styles.filters} role="search" aria-label="Filter devices">
        <Field label="Site">
          <Select value={query.siteId ?? ''} onChange={(_, d) => change({ siteId: d.value })}>
            <option value="">All sites</option>
            {sites.data?.map((site) => (
              <option key={site.siteId} value={site.siteId}>
                {site.displayName} ({site.deviceCount})
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Status">
          <Select value={query.status ?? ''} onChange={(_, d) => change({ status: d.value })}>
            <option value="">Any status</option>
            <option value="reporting">Reporting</option>
            <option value="silent">Silent</option>
          </Select>
        </Field>
        <Field label="Search" className={styles.search}>
          <SearchBox
            placeholder="Device id or name"
            value={searchText}
            maxLength={100}
            onChange={(_, d) => setSearchText(d.value)}
          />
        </Field>
        {hasFilters && (
          <Button
            appearance="subtle"
            onClick={() => {
              setSearchText('')
              change({ siteId: undefined, status: undefined, search: undefined })
            }}
          >
            Clear filters
          </Button>
        )}
      </div>

      {devices.isError && !data ? (
        <ErrorState title="The devices could not be loaded" error={devices.error} onRetry={() => void devices.refetch()} />
      ) : !data ? (
        <Spinner label="Loading devices…" />
      ) : (
        <>
          <Table sortable aria-label="Devices" aria-busy={devices.isFetching}>
            <TableHeader>
              <TableRow>
                {COLUMNS.map((column) =>
                  column.sort ? (
                    <TableHeaderCell
                      key={column.label}
                      sortDirection={query.sort === column.sort ? (query.order === 'asc' ? 'ascending' : 'descending') : undefined}
                      onClick={() => change({ sort: column.sort, order: nextOrder(query, column.sort!) })}
                    >
                      {column.label}
                    </TableHeaderCell>
                  ) : (
                    <TableHeaderCell key={column.label}>{column.label}</TableHeaderCell>
                  ),
                )}
                {canWrite && <TableHeaderCell><span className="visually-hidden">Actions</span></TableHeaderCell>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.items.map((device) => (
                <DeviceRow key={device.deviceId} device={device} canWrite={canWrite} onEdit={() => setEditing(device.deviceId)} />
              ))}
            </TableBody>
          </Table>

          {data.total === 0 ? (
            <div className={styles.empty}>
              <Body1 as="p">{hasFilters ? 'No devices match these filters.' : 'No device has reported data yet.'}</Body1>
            </div>
          ) : (
            <nav className={styles.pager} aria-label="Pages">
              <Body1 aria-live="polite">
                {numberFormat.format((query.page - 1) * query.pageSize + 1)}–
                {numberFormat.format(Math.min(query.page * query.pageSize, data.total))} of {numberFormat.format(data.total)} devices
                {' · '}page {query.page} of {pageCount}
              </Body1>
              <Button
                icon={<ChevronLeftRegular />}
                disabled={query.page <= 1}
                onClick={() => change({ page: String(query.page - 1) })}
              >
                Previous
              </Button>
              <Button
                icon={<ChevronRightRegular />}
                iconPosition="after"
                disabled={query.page >= pageCount}
                onClick={() => change({ page: String(query.page + 1) })}
              >
                Next
              </Button>
            </nav>
          )}
        </>
      )}

      {editing && <EditDeviceDialog deviceId={editing} onClose={() => setEditing(undefined)} />}
    </section>
  )
}

function DeviceRow({ device, canWrite, onEdit }: { device: DeviceSummary; canWrite: boolean; onEdit: () => void }) {
  const styles = useStyles()
  const name = device.displayName ?? device.deviceId
  return (
    <TableRow>
      <TableCell>
        <TableCellLayout description={device.displayName ? <Caption1 className={styles.secondary}>{device.deviceId}</Caption1> : undefined}>
          {name}
        </TableCellLayout>
      </TableCell>
      <TableCell>{device.siteId}</TableCell>
      <TableCell>{device.lastPayloadFormat}</TableCell>
      <TableCell><Timestamp value={device.firstSeenAt} /></TableCell>
      <TableCell><Timestamp value={device.lastSeenAt} /></TableCell>
      <TableCell><StatusBadge status={device.status} /></TableCell>
      {canWrite && (
        <TableCell>
          <Button appearance="subtle" icon={<EditRegular />} aria-label={`Edit ${name}`} onClick={onEdit} />
        </TableCell>
      )}
    </TableRow>
  )
}
