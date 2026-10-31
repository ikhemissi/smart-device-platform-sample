import { Body1, Button, DialogActions, DialogBody, DialogContent, DialogTitle, Field, MessageBar, MessageBarBody, MessageBarTitle, makeStyles, tokens } from '@fluentui/react-components'
import { type FormEvent, useId, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useAddSiteCertificate } from '../../api/queries'
import type { CertificateChange } from '../../api/types'
import { ModalDialog } from '../../components/ModalDialog'
import { checkFile } from './trust'

const useStyles = makeStyles({ form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM } })

interface Props {
  siteId: string
  onClose: () => void
  onAdded: (change: CertificateChange) => void
}

export function UploadCertificateDialog({ open, ...props }: Props & { open: boolean }) {
  return (
    <ModalDialog open={open} onClose={props.onClose}>
      {open && <UploadCertificateForm {...props} />}
    </ModalDialog>
  )
}

function UploadCertificateForm({ siteId, onClose, onAdded }: Props) {
  const styles = useStyles()
  const inputId = useId()
  const add = useAddSiteCertificate(siteId)
  const [file, setFile] = useState<File>()
  const [error, setError] = useState<{ title: string; detail: string }>()

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const invalid = checkFile(file)
    if (invalid) {
      setError({ title: 'The file cannot be used', detail: invalid })
      return
    }
    try {
      onAdded(await add.mutateAsync(file!))
    } catch (e) {
      setError({ title: 'The certificate was not added', detail: rejectionDetail(e) })
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <DialogBody>
        <DialogTitle>Add a certificate to {siteId}</DialogTitle>
        <DialogContent className={styles.form}>
          {error && (
            <MessageBar intent="error" layout="multiline" role="alert">
              <MessageBarBody>
                <MessageBarTitle>{error.title}</MessageBarTitle>
                {error.detail}
              </MessageBarBody>
            </MessageBar>
          )}
          <Body1>
            Upload the certificate of the authority (CA) that issues this site's device certificates. Devices it issued can
            join within 5 minutes. Never upload a private key.
          </Body1>
          <Field label={{ children: 'Certificate file', htmlFor: inputId }} required hint="PEM or DER, at most 64 KB">
            <input
              id={inputId}
              type="file"
              accept=".pem,.crt,.cer,.der"
              onChange={(event) => {
                setFile(event.target.files?.[0])
                setError(undefined)
              }}
            />
          </Field>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button appearance="primary" type="submit" disabled={add.isPending}>
            {add.isPending ? 'Adding…' : 'Add certificate'}
          </Button>
        </DialogActions>
      </DialogBody>
    </form>
  )
}

/** Why the API did not add the certificate: its own explanation of an invalid certificate, or a generic one. */
function rejectionDetail(error: unknown): string {
  if (error instanceof ApiError && (error.status === 400 || error.status === 409)) {
    return error.problem.detail ?? error.message
  }
  if (error instanceof ApiError && error.status === 503) {
    return 'Device provisioning could not be updated right now; nothing was changed. Try again in a moment.'
  }
  return explain(error)
}
