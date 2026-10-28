import { Badge } from '@fluentui/react-components'
import type { TrustStatus } from './trust'

const BADGES: Record<TrustStatus, { label: string; color: 'success' | 'warning' | 'danger' | 'informative' }> = {
  valid: { label: 'Valid', color: 'success' },
  expiring: { label: 'Expiring', color: 'warning' },
  expired: { label: 'Expired', color: 'danger' },
  none: { label: 'No certificate', color: 'informative' },
}

export function TrustBadge({ status }: { status: TrustStatus }) {
  const badge = BADGES[status]
  return (
    <Badge appearance="tint" color={badge.color} shape="rounded">
      {badge.label}
    </Badge>
  )
}
