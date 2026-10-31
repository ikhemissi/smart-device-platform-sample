import { Body1, Button, DialogActions, DialogBody, DialogContent, DialogTitle } from '@fluentui/react-components'
import { useState } from 'react'
import { explain } from '../../api/errors'
import { useDeleteThreshold } from '../../api/queries'
import type { Threshold } from '../../api/types'
import { FormError } from '../../components/FormError'
import { ModalDialog } from '../../components/ModalDialog'
import { rangeText } from './alertText'

/** Destructive action: nothing happens until confirmed. */
export function DeleteThresholdDialog(props: { deviceId: string; threshold: Threshold | undefined; label: string; onClose: () => void }) {
  return (
    <ModalDialog open={!!props.threshold} modalType="alert" onClose={props.onClose}>
      {props.threshold && <DeleteThresholdForm {...props} threshold={props.threshold} />}
    </ModalDialog>
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
        <FormError message={error} />
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
