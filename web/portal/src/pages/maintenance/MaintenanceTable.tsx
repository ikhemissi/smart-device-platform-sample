import {
  Body1,
  Button,
  Caption1,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { Link } from 'react-router'
import type { MaintenanceItem } from '../../api/types'
import { formatAbsolute, formatRelative } from '../../format'
import { MaintenanceStatusBadge } from './MaintenanceStatusBadge'
import { daysLeftText, percentText, reasonText } from './maintenanceText'

const useStyles = makeStyles({
  scroll: { overflowX: 'auto' },
  cell: { display: 'flex', flexDirection: 'column', paddingTop: tokens.spacingVerticalS, paddingBottom: tokens.spacingVerticalS },
  reasons: { margin: 0, paddingLeft: tokens.spacingHorizontalL },
  workOrder: { minWidth: '18rem', maxWidth: '32rem', whiteSpace: 'pre-line' },
  secondary: { color: tokens.colorNeutralForeground3 },
  link: { color: tokens.colorBrandForegroundLink, ':hover': { textDecoration: 'underline' } },
  actions: { display: 'flex', gap: tokens.spacingHorizontalS },
})

export type StatusAction = 'planned' | 'done'

interface Props {
  items: MaintenanceItem[]
  /** Accessible name of the table. */
  label: string
  /** Editors: mark an item planned or done. */
  onStatus?: (item: MaintenanceItem, status: StatusAction) => void
}

/** Queue items, most likely first: device, probability, days left, reasons, work order and status. */
export function MaintenanceTable({ items, label, onStatus }: Props) {
  const styles = useStyles()
  return (
    <div className={styles.scroll}>
      <Table aria-label={label} size="small">
        <TableHeader>
          <TableRow>
            <TableHeaderCell>Device</TableHeaderCell>
            <TableHeaderCell>Probability</TableHeaderCell>
            <TableHeaderCell>Days left</TableHeaderCell>
            <TableHeaderCell>Reasons</TableHeaderCell>
            <TableHeaderCell className={styles.workOrder}>Work order</TableHeaderCell>
            <TableHeaderCell>Status</TableHeaderCell>
            {onStatus && <TableHeaderCell><span className="visually-hidden">Actions</span></TableHeaderCell>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((item) => {
            const device = item.deviceName ?? item.deviceId
            const open = item.status === 'open' || item.status === 'planned'
            return (
              <TableRow key={item.id} data-status={item.status}>
                <TableCell>
                  <div className={styles.cell}>
                    <Link to={`/devices/${encodeURIComponent(item.deviceId)}`} className={styles.link}>{device}</Link>
                    <Caption1 className={styles.secondary}>{item.siteId}</Caption1>
                  </div>
                </TableCell>
                <TableCell>{percentText(item.probability)}</TableCell>
                <TableCell>{daysLeftText(item.battery)}</TableCell>
                <TableCell>
                  <ul className={styles.reasons}>
                    {item.reasons.map((reason, i) => (
                      <li key={i}>{reasonText(reason)}</li>
                    ))}
                  </ul>
                </TableCell>
                <TableCell className={styles.workOrder}>
                  <Body1 className={styles.cell}>
                    {item.workOrder ?? <Caption1 className={styles.secondary}>Not written yet</Caption1>}
                  </Body1>
                </TableCell>
                <TableCell>
                  <div className={styles.cell}>
                    <span><MaintenanceStatusBadge status={item.status} /></span>
                    {item.statusAt && (
                      <Caption1 title={formatAbsolute(item.statusAt)}>
                        {item.statusBy ? `by ${item.statusBy}, ` : ''}
                        {formatRelative(item.statusAt)}
                      </Caption1>
                    )}
                    {item.statusComment && <Body1>“{item.statusComment}”</Body1>}
                  </div>
                </TableCell>
                {onStatus && (
                  <TableCell>
                    {open && (
                      <div className={styles.actions}>
                        {item.status === 'open' && (
                          <Button size="small" aria-label={`Mark the visit to ${device} planned`} onClick={() => onStatus(item, 'planned')}>
                            Planned
                          </Button>
                        )}
                        <Button size="small" aria-label={`Mark the visit to ${device} done`} onClick={() => onStatus(item, 'done')}>
                          Done
                        </Button>
                      </div>
                    )}
                  </TableCell>
                )}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
