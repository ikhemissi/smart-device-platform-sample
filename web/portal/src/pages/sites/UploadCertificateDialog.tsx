import { Body1, Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Field, MessageBar, MessageBarBody, MessageBarTitle, makeStyles, tokens } from '@fluentui/react-components'
import { type FormEvent, useId, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useAddSiteCertificate } from '../../api/queries'
import type { CertificateChange } from '../../api/types'
import { checkFile } from './trust'
import { useOpenKey } from '../../components/useOpenKey'

const useStyles = makeStyles({ form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM } })


export function UploadCertificateDialog(props: { open: boolean; siteId: string; onClose: () => void; onAdded: (change: CertificateChange) => void }) {
  const key = useOpenKey(props.open)
  return (
    <Dialog key={key} open={props.open} modalType="modal" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface aria-describedby={undefined}>
        {props.open && <UploadCertificateForm siteId={props.siteId} onClose={props.onClose} onAdded={props.onAdded} />}
      </DialogSurface>
    </Dialog>
  )
}

function UploadCertificateForm(props: { siteId: string; onClose: () => void; onAdded: (change: CertificateChange) => void }) {
  const styles = useStyles()
  const inputId = useId()
  const add = useAddSiteCertificate(props.siteId)
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
      props.onAdded(await add.mutateAsync(file!))
    } catch (e) {
      if (e instanceof ApiError && (e.status === 400 || e.status === 409)) {
        setError({ title: 'The certificate was not added', detail: e.problem.detail ?? e.message })
      } else if (e instanceof ApiError && e.status === 503) {
        setError({ title: 'The certificate was not added', detail: 'Device provisioning could not be updated right now; nothing was changed. Try again in a moment.' })
      } else {
        setError({ title: 'The certificate was not added', detail: explain(e) })
      }
    }
  }

  return (
    <>
        <form onSubmit={(event) => void submit(event)} noValidate>
          <DialogBody>
            <DialogTitle>Add a certificate to {props.siteId}</DialogTitle>
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
              <Button onClick={props.onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={add.isPending}>
                {add.isPending ? 'Adding…' : 'Add certificate'}
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </>
  )
}
