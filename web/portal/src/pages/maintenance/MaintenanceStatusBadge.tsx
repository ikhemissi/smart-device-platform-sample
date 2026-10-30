import { Badge } from '@fluentui/react-components'
import type { MaintenanceStatus } from '../../api/types'
import { STATUS_LABELS } from './maintenanceText'

const BADGES: Record<MaintenanceStatus, { appearance: 'filled' | 'tint' | 'outline'; color: 'warning' | 'brand' | 'success' | 'subtle' }> = {
  open: { appearance: 'filled', color: 'warning' },
  planned: { appearance: 'filled', color: 'brand' },
  done: { appearance: 'tint', color: 'success' },
  resolved: { appearance: 'tint', color: 'subtle' },
}

export function MaintenanceStatusBadge({ status }: { status: MaintenanceStatus }) {
  return (
    <Badge shape="rounded" {...BADGES[status]}>
      {STATUS_LABELS[status]}
    </Badge>
  )
}
