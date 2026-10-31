import { Button, DialogActions, DialogBody, DialogContent, DialogTitle, Field, Input, MessageBar, MessageBarBody, makeStyles, tokens } from '@fluentui/react-components'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useCreateSite } from '../../api/queries'
import { FormError } from '../../components/FormError'
import { ModalDialog } from '../../components/ModalDialog'
import { SITE_ID_PATTERN } from './trust'

const useStyles = makeStyles({ form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM } })

export function CreateSiteDialog(props: { open: boolean; onClose: () => void; onCreated: (siteId: string) => void }) {
  return (
    <ModalDialog open={props.open} onClose={props.onClose}>
      {props.open && <CreateSiteForm onClose={props.onClose} onCreated={props.onCreated} />}
    </ModalDialog>
  )
}

function CreateSiteForm({ onClose, onCreated }: { onClose: () => void; onCreated: (siteId: string) => void }) {
  const styles = useStyles()
  const create = useCreateSite()
  const [siteId, setSiteId] = useState('')
  const [name, setName] = useState('')
  const [errors, setErrors] = useState<{ siteId?: string; name?: string; form?: string }>({})

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const id = siteId.trim()
    const next: typeof errors = {}
    if (id.length < 3 || id.length > 40 || !SITE_ID_PATTERN.test(id)) {
      next.siteId = 'Use 3 to 40 lowercase letters, digits and single hyphens, e.g. nice-01.'
    }
    if (!name.trim() || name.trim().length > 100) {
      next.name = 'Enter a name of at most 100 characters.'
    }
    setErrors(next)
    if (Object.keys(next).length > 0) return
    try {
      await create.mutateAsync({ siteId: id, displayName: name.trim() })
      onCreated(id)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setErrors({ siteId: `A site with id ${id} already exists.` })
      } else {
        setErrors({ form: explain(error) })
      }
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <DialogBody>
        <DialogTitle>Create a site</DialogTitle>
        <DialogContent className={styles.form}>
          <FormError message={errors.form} />
          <Field label="Site id" required validationMessage={errors.siteId} hint="Lowercase letters, digits and hyphens. Cannot be changed later.">
            <Input value={siteId} onChange={(_, d) => setSiteId(d.value)} />
          </Field>
          <Field label="Name" required validationMessage={errors.name}>
            <Input value={name} onChange={(_, d) => setName(d.value)} />
          </Field>
          <MessageBar intent="info">
            <MessageBarBody>No device can join a new site until you add a certificate authority to it.</MessageBarBody>
          </MessageBar>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button appearance="primary" type="submit" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create site'}
          </Button>
        </DialogActions>
      </DialogBody>
    </form>
  )
}
