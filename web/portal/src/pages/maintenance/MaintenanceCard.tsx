import { Body1, Caption1, Card, Spinner, Subtitle1, makeStyles, tokens } from '@fluentui/react-components'
import { type ReactNode, useState } from 'react'
import { useCurrentUser, useDeviceMaintenance } from '../../api/queries'
import type { DeviceMaintenance } from '../../api/types'
import { ErrorState } from '../../components/ErrorState'
import { Timestamp } from '../../components/Timestamp'
import { MaintenanceStatusDialog, type StatusChange } from './MaintenanceStatusDialog'
import { MaintenanceTable } from './MaintenanceTable'
import { daysLeftText, levelText, percentText, trendText } from './maintenanceText'

const useStyles = makeStyles({
  facts: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalXXL, margin: 0 },
  fact: { display: 'flex', flexDirection: 'column', margin: 0 },
  value: { margin: 0 },
})

/** The device's battery forecast, intervention probability and queue item (Editors plan or close it). */
export function MaintenanceCard({ deviceId }: { deviceId: string }) {
  const maintenance = useDeviceMaintenance(deviceId)
  return (
    <Card>
      <Subtitle1 as="h2">Battery and maintenance</Subtitle1>
      {maintenance.isError && !maintenance.data ? (
        <ErrorState title="The maintenance status could not be loaded" error={maintenance.error} onRetry={() => void maintenance.refetch()} />
      ) : !maintenance.data ? (
        <Spinner size="tiny" label="Loading the maintenance status…" />
      ) : !maintenance.data.enabled ? (
        <Body1>Maintenance is not assessed for this site.</Body1>
      ) : !maintenance.data.assessment ? (
        <Body1>This device has not been assessed yet.</Body1>
      ) : (
        <Assessed maintenance={maintenance.data} />
      )}
    </Card>
  )
}

function Assessed({ maintenance }: { maintenance: DeviceMaintenance }) {
  const styles = useStyles()
  const canWrite = useCurrentUser().data?.canWrite === true
  const [changing, setChanging] = useState<StatusChange>()
  const { assessment, item } = maintenance
  const battery = assessment!.battery
  const facts: [string, ReactNode][] = [
    ['Battery level', battery ? levelText(battery) : 'No battery reported'],
    ['Trend', battery ? (trendText(battery) ?? 'Not enough history') : '–'],
    ['Days left', daysLeftText(battery)],
    ['Intervention probability (7 days)', percentText(assessment!.probability)],
    ['Assessed', <Timestamp key="assessed" value={assessment!.assessedAt} />],
  ]
  return (
    <>
      <dl className={styles.facts}>
        {facts.map(([label, value]) => (
          <div key={label} className={styles.fact}>
            <dt><Caption1>{label}</Caption1></dt>
            <dd className={styles.value}><Body1>{value}</Body1></dd>
          </div>
        ))}
      </dl>
      {item ? (
        <MaintenanceTable items={[item]} label="Maintenance item" onStatus={canWrite ? (i, status) => setChanging({ item: i, status }) : undefined} />
      ) : (
        <Body1>Not in the maintenance queue.</Body1>
      )}
      {canWrite && <MaintenanceStatusDialog change={changing} onClose={() => setChanging(undefined)} />}
    </>
  )
}
