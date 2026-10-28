import type { Citation, ToolActivity } from '../../api/types'

const day = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

/** Portal link of a citation: the device page over the cited period and metrics, or the site page. */
export function citationLink(citation: Citation): { to: string; label: string } | null {
  if (citation.kind === 'site' && citation.siteId) {
    return { to: `/sites/${encodeURIComponent(citation.siteId)}`, label: `Site ${citation.siteId}` }
  }
  if (citation.kind !== 'device' || !citation.deviceId) {
    return null
  }
  const params = new URLSearchParams()
  let label = citation.deviceId
  if (citation.from && citation.to) {
    params.set('period', 'custom')
    params.set('from', citation.from)
    params.set('to', citation.to)
    label += ` · ${day.format(new Date(citation.from))} – ${day.format(new Date(citation.to))}`
  }
  if (citation.metrics?.length) {
    params.set('metrics', citation.metrics.join(','))
  }
  const query = params.toString()
  return { to: `/devices/${encodeURIComponent(citation.deviceId)}${query ? `?${query}` : ''}`, label }
}

/** What a tool call is doing, for the activity indicator. */
export function toolLabel(tool: ToolActivity): string {
  const a = tool.arguments
  const site = a.site_id ? ` of ${String(a.site_id)}` : ''
  const device = a.device_id ? String(a.device_id) : 'a device'
  switch (tool.name) {
    case 'list_sites':
      return 'Listing sites'
    case 'fleet_status':
      return `Checking which devices report${site}`
    case 'metric_summary':
      return `Summarising ${String(a.metric ?? 'a metric')}${a.site_id ? ` at ${String(a.site_id)}` : ' across sites'}`
    case 'search_devices':
      return `Searching devices${site}`
    case 'get_device':
      return `Reading ${device}`
    case 'get_latest_measurements':
      return `Reading the latest measurements of ${device}`
    case 'get_series':
      return `Reading the ${Array.isArray(a.metrics) ? a.metrics.join(', ') : 'measurement'} history of ${device}`
    case 'list_alerts':
      return `Checking alerts${a.device_id ? ` of ${device}` : site}`
    case 'get_thresholds':
      return `Reading the thresholds of ${device}`
    default:
      return `Consulting ${tool.name}`
  }
}
