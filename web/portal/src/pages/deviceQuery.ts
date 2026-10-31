import type { DeviceQuery, DeviceStatus, SortField, SortOrder } from '../api/types'
import { withParams } from '../searchParams'

const PAGE_SIZE = 25
const SORT_FIELDS: SortField[] = ['displayName', 'siteId', 'firstSeen', 'lastSeen']
const STATUSES: DeviceStatus[] = ['reporting', 'silent']

/** The list state lives in the URL (shareable, survives reloads); unknown values fall back to defaults. */
export function readDeviceQuery(params: URLSearchParams): DeviceQuery {
  const sort = params.get('sort') as SortField
  const status = params.get('status') as DeviceStatus
  const page = Number.parseInt(params.get('page') ?? '', 10)
  return {
    siteId: params.get('siteId') || undefined,
    status: STATUSES.includes(status) ? status : undefined,
    search: params.get('search')?.trim() || undefined,
    sort: SORT_FIELDS.includes(sort) ? sort : 'displayName',
    order: params.get('order') === 'desc' ? 'desc' : 'asc',
    page: Number.isFinite(page) && page > 0 ? page : 1,
    pageSize: PAGE_SIZE,
  }
}

export type DeviceListChanges = Partial<Record<'siteId' | 'status' | 'search' | 'sort' | 'order' | 'page', string | undefined>>

/** Applies changes to the URL parameters; any filter or sort change goes back to the first page. */
export function updateParams(params: URLSearchParams, changes: DeviceListChanges): URLSearchParams {
  const next = withParams(params, changes)
  if (!('page' in changes)) {
    next.delete('page')
  }
  return next
}

export function nextOrder(query: DeviceQuery, field: SortField): SortOrder {
  return query.sort === field && query.order === 'asc' ? 'desc' : 'asc'
}
