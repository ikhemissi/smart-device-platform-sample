import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  MessageBarTitle,
  Spinner,
  Textarea,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { type FormEvent, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { useDevice, useUpdateDevice } from '../api/queries'
import { explain } from '../api/errors'
import { ErrorState } from '../components/ErrorState'
import { type DeviceForm, type FormErrors, LIMITS, fromProblemErrors, toEdit, toForm } from './deviceForm'
import { useOpenKey } from '../components/useOpenKey'

const useStyles = makeStyles({
  form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
  row: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: tokens.spacingHorizontalM },
})

/**
 * Editor-only device edit. The Fluent dialog stays mounted and is driven by `deviceId` (open when set): unmounting an open
 * modal would leave the rest of the portal hidden from assistive technology.
 */
export function EditDeviceDialog({ deviceId, onClose }: { deviceId: string | undefined; onClose: () => void }) {
  const key = useOpenKey(!!deviceId)
  return (
    <Dialog key={key} open={!!deviceId} modalType="modal" onOpenChange={(_, data) => !data.open && onClose()}>
      <DialogSurface aria-describedby={undefined}>{deviceId && <EditDeviceForm deviceId={deviceId} onClose={onClose} />}</DialogSurface>
    </Dialog>
  )
}

/** Editor-only dialog: validates like the API, sends the entity tag and handles concurrent changes. */
function EditDeviceForm({ deviceId, onClose }: { deviceId: string; onClose: () => void }) {
  const styles = useStyles()
  const device = useDevice(deviceId)
  const update = useUpdateDevice(deviceId)
  const [form, setForm] = useState<DeviceForm>()
  const [errors, setErrors] = useState<FormErrors>({})
  const [conflict, setConflict] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  // (Re)load the form from the latest version: on open and after a conflict.
  const etag = device.data?.etag
  useEffect(() => {
    if (device.data) {
      setForm(toForm(device.data.value))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when a new version arrives
  }, [etag])

  const set = (field: keyof DeviceForm) => (value: string) => {
    setForm((current) => current && { ...current, [field]: value })
    setErrors((current) => ({ ...current, [field]: undefined }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!form || !device.data) {
      return
    }
    const { edit, errors: invalid } = toEdit(form)
    setErrors(invalid)
    setFailure(undefined)
    if (!edit) {
      return
    }
    try {
      await update.mutateAsync({ etag: device.data.etag, edit })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 412) {
        setConflict(true)
        await device.refetch()
      } else if (error instanceof ApiError && error.status === 400 && error.problem.errors) {
        setErrors(fromProblemErrors(error.problem.errors))
      } else {
        setFailure(error)
      }
    }
  }

  const name = device.data?.value.displayName ?? deviceId
  return (
    <>
        <form onSubmit={(event) => void submit(event)} noValidate>
          <DialogBody>
            <DialogTitle>Edit {name}</DialogTitle>
            <DialogContent className={styles.form}>
              {device.isError && !device.data ? (
                <ErrorState title="The device could not be loaded" error={device.error} onRetry={() => void device.refetch()} />
              ) : !form ? (
                <Spinner label="Loading device…" />
              ) : (
                <>
                  {conflict && (
                    <MessageBar intent="warning" layout="multiline" role="alert">
                      <MessageBarBody>
                        <MessageBarTitle>This device was changed by someone else</MessageBarTitle>
                        The latest values are now shown. Review them and apply your changes again.
                      </MessageBarBody>
                    </MessageBar>
                  )}
                  {failure !== undefined && (
                    <MessageBar intent="error" layout="multiline" role="alert">
                      <MessageBarBody>
                        <MessageBarTitle>Your changes could not be saved</MessageBarTitle>
                        {explain(failure)}
                      </MessageBarBody>
                    </MessageBar>
                  )}
                  <Field label="Display name" validationMessage={errors.displayName} hint={`Up to ${LIMITS.displayName} characters`}>
                    <Input value={form.displayName} onChange={(_, d) => set('displayName')(d.value)} />
                  </Field>
                  <Field label="Description" validationMessage={errors.description} hint={`Up to ${LIMITS.description} characters`}>
                    <Textarea value={form.description} resize="vertical" onChange={(_, d) => set('description')(d.value)} />
                  </Field>
                  <div className={styles.row}>
                    <Field label="Latitude" validationMessage={errors.latitude} hint="-90 to 90">
                      <Input inputMode="decimal" value={form.latitude} onChange={(_, d) => set('latitude')(d.value)} />
                    </Field>
                    <Field label="Longitude" validationMessage={errors.longitude} hint="-180 to 180">
                      <Input inputMode="decimal" value={form.longitude} onChange={(_, d) => set('longitude')(d.value)} />
                    </Field>
                  </div>
                  <Field label="Location label" validationMessage={errors.label} hint={`Up to ${LIMITS.label} characters`}>
                    <Input value={form.label} onChange={(_, d) => set('label')(d.value)} />
                  </Field>
                </>
              )}
            </DialogContent>
            <DialogActions>
              <Button appearance="secondary" onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!form || update.isPending}>
                {update.isPending ? 'Saving…' : 'Save'}
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </>
  )
}
