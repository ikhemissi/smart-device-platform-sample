import { Badge } from '@fluentui/react-components'
import type { DeviceStatus } from '../api/types'

export function StatusBadge({ status }: { status: DeviceStatus }) {
  return status === 'reporting' ? (
    <Badge appearance="tint" color="success" shape="rounded">Reporting</Badge>
  ) : (
    <Badge appearance="tint" color="warning" shape="rounded">Silent</Badge>
  )
}
