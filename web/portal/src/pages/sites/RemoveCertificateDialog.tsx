import { Body1, Button, DialogActions, DialogBody, DialogContent, DialogTitle, MessageBar, MessageBarBody, makeStyles, tokens } from '@fluentui/react-components'
import { useState } from 'react'
import { explain } from '../../api/errors'
import { useRemoveSiteCertificate } from '../../api/queries'
import type { CertificateChange, SiteCertificate } from '../../api/types'
import { FormError } from '../../components/FormError'
import { ModalDialog } from '../../components/ModalDialog'

const useStyles = makeStyles({ content: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM } })

interface RemoveProps {
  /** Whether it is the site's last certificate (its devices are then disabled). */
  last: boolean
  deviceCount: number | undefined
  onClose: () => void
  onRemoved: (change: CertificateChange) => void
}

/** Destructive action: nothing happens until confirmed; removing the last certificate warns that devices are disabled. */
export function RemoveCertificateDialog({ certificate, ...props }: RemoveProps & { certificate: SiteCertificate | undefined }) {
  return (
    <ModalDialog open={!!certificate} modalType="alert" onClose={props.onClose}>
      {certificate && <RemoveCertificateForm certificate={certificate} {...props} />}
    </ModalDialog>
  )
}

function RemoveCertificateForm({ certificate, last, deviceCount, onClose, onRemoved }: RemoveProps & { certificate: SiteCertificate }) {
  const styles = useStyles()
  const remove = useRemoveSiteCertificate(certificate.siteId)
  const [error, setError] = useState<string>()
  const site = certificate.siteId
  const devices = deviceCount === undefined ? 'all devices' : `all ${deviceCount} device${deviceCount === 1 ? '' : 's'}`

  const confirm = async () => {
    try {
      onRemoved(await remove.mutateAsync(certificate.fingerprint))
    } catch (e) {
      setError(explain(e))
    }
  }

  return (
    <DialogBody>
      <DialogTitle>{last ? `Remove the last certificate of ${site}?` : 'Remove this certificate?'}</DialogTitle>
      <DialogContent className={styles.content}>
        <FormError message={error} />
        <Body1>
          Devices whose certificate was issued by <strong>{certificate.subject}</strong> will no longer be able to join{' '}
          {site}.
        </Body1>
        {last ? (
          <MessageBar intent="warning" layout="multiline">
            <MessageBarBody>
              This is the site's last certificate: {devices} of {site} will be disabled and stop sending data. They are
              re-enabled when you add a certificate again. Measurements already received are kept.
            </MessageBarBody>
          </MessageBar>
        ) : (
          <Body1>The site's other certificate keeps working. Devices already connected keep sending until they re-join.</Body1>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button appearance="primary" onClick={() => void confirm()} disabled={remove.isPending}>
          {remove.isPending ? 'Removing…' : last ? 'Remove and disable devices' : 'Remove certificate'}
        </Button>
      </DialogActions>
    </DialogBody>
  )
}
