import { Body1, Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, MessageBar, MessageBarBody } from '@fluentui/react-components'
import { useState } from 'react'
import { explain } from '../../api/errors'
import { useDeleteThreshold } from '../../api/queries'
import type { Threshold } from '../../api/types'
import { useOpenKey } from '../../components/useOpenKey'
import { rangeText } from './thresholdForm'

/** Destructive action: nothing happens until confirmed. */
export function DeleteThresholdDialog(props: { deviceId: string; threshold: Threshold | undefined; label: string; onClose: () => void }) {
  const key = useOpenKey(!!props.threshold)
  return (
    <Dialog key={key} open={!!props.threshold} modalType="alert" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface>{props.threshold && <DeleteThresholdForm {...props} threshold={props.threshold} />}</DialogSurface>
    </Dialog>
  )
}

function DeleteThresholdForm({ deviceId, threshold, label, onClose }: { deviceId: string; threshold: Threshold; label: string; onClose: () => void }) {
  const remove = useDeleteThreshold(deviceId)
  const [error, setError] = useState<string>()
  const confirm = async () => {
    try {
      await remove.mutateAsync(threshold)
      onClose()
    } catch (e) {
      setError(explain(e))
    }
  }
  return (
    <DialogBody>
      <DialogTitle>Delete this threshold?</DialogTitle>
      <DialogContent>
        {error && (
          <MessageBar intent="error" role="alert">
            <MessageBarBody>{error}</MessageBarBody>
          </MessageBar>
        )}
        <Body1>
          {label} {rangeText(threshold.min, threshold.max, threshold.unit)} will no longer be watched.
          {threshold.openAlertId ? ' Its open alert is closed without sending an email.' : ''} Past alerts are kept.
        </Body1>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button appearance="primary" onClick={() => void confirm()} disabled={remove.isPending}>
          {remove.isPending ? 'Deleting…' : 'Delete threshold'}
        </Button>
      </DialogActions>
    </DialogBody>
  )
}
