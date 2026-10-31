import { Body1, Button, DialogActions, DialogBody, DialogContent, DialogTitle, makeStyles, tokens } from '@fluentui/react-components'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useSetMaintenanceStatus } from '../../api/queries'
import type { MaintenanceItem } from '../../api/types'
import { CommentField } from '../../components/CommentField'
import { FormError } from '../../components/FormError'
import { ModalDialog } from '../../components/ModalDialog'
import type { StatusAction } from './MaintenanceTable'
import { percentText } from './maintenanceText'

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
  return (
    <ModalDialog open={!!props.change} onClose={props.onClose}>
      {props.change && <StatusForm {...props} change={props.change} />}
    </ModalDialog>
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
          <FormError message={errors.form} />
          <Body1>
            {item.deviceName ?? item.deviceId} ({item.siteId}), intervention probability {percentText(item.probability)}:{' '}
            {status === 'planned' ? 'the visit is planned.' : 'the visit is done; the item leaves the queue.'}
          </Body1>
          <CommentField value={comment} error={errors.comment} onChange={setComment} />
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
