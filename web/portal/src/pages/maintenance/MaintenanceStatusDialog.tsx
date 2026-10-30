import {
  Body1,
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  MessageBar,
  MessageBarBody,
  Textarea,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useSetMaintenanceStatus } from '../../api/queries'
import type { MaintenanceItem } from '../../api/types'
import { useOpenKey } from '../../components/useOpenKey'
import type { StatusAction } from './MaintenanceTable'
import { percentText } from './maintenanceText'

export const MAX_COMMENT = 1000

const useStyles = makeStyles({
  form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
})

export interface StatusChange {
  item: MaintenanceItem
  status: StatusAction
}

interface Props {
  /** The item and its new status; undefined when closed. */
  change: StatusChange | undefined
  onClose: () => void
  onChanged?: (item: MaintenanceItem) => void
}

const TITLES: Record<StatusAction, string> = { planned: 'Plan this visit', done: 'Close this visit' }

/** Editors mark a queue item planned or done, with an optional comment. */
export function MaintenanceStatusDialog(props: Props) {
  const key = useOpenKey(!!props.change)
  return (
    <Dialog key={key} open={!!props.change} modalType="modal" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface aria-describedby={undefined}>{props.change && <StatusForm {...props} change={props.change} />}</DialogSurface>
    </Dialog>
  )
}

type Errors = Partial<Record<'comment' | 'form', string>>

function StatusForm({ change: { item, status }, onClose, onChanged }: Props & { change: StatusChange }) {
  const styles = useStyles()
  const mutation = useSetMaintenanceStatus()
  const [comment, setComment] = useState(item.statusComment ?? '')
  const [errors, setErrors] = useState<Errors>({})

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setErrors({})
    try {
      const changed = await mutation.mutateAsync({ id: item.id, status, comment: comment.trim() || null })
      onChanged?.(changed)
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setErrors({ form: 'This item is already closed.' })
      } else if (error instanceof ApiError && error.status === 404) {
        setErrors({ form: 'This item no longer exists.' })
      } else if (error instanceof ApiError && error.problem.errors) {
        setErrors({ comment: error.problem.errors.comment?.[0], form: explain(error) })
      } else {
        setErrors({ form: explain(error) })
      }
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <DialogBody>
        <DialogTitle>{TITLES[status]}</DialogTitle>
        <DialogContent className={styles.form}>
          {errors.form && (
            <MessageBar intent="error" role="alert">
              <MessageBarBody>{errors.form}</MessageBarBody>
            </MessageBar>
          )}
          <Body1>
            {item.deviceName ?? item.deviceId} ({item.siteId}), intervention probability {percentText(item.probability)}:{' '}
            {status === 'planned' ? 'the visit is planned.' : 'the visit is done; the item leaves the queue.'}
          </Body1>
          <Field label="Comment" hint={`Optional · ${comment.length}/${MAX_COMMENT} characters`} validationMessage={errors.comment}>
            <Textarea resize="vertical" maxLength={MAX_COMMENT} value={comment} onChange={(_, d) => setComment(d.value)} />
          </Field>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button appearance="primary" type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : status === 'planned' ? 'Mark planned' : 'Mark done'}
          </Button>
        </DialogActions>
      </DialogBody>
    </form>
  )
}
