import { Body1, Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, MessageBar, MessageBarBody, makeStyles, tokens } from '@fluentui/react-components'
import { useState } from 'react'
import { explain } from '../../api/errors'
import { useRemoveSiteCertificate } from '../../api/queries'
import type { CertificateChange, SiteCertificate } from '../../api/types'
import { useOpenKey } from '../../components/useOpenKey'

const useStyles = makeStyles({ content: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM } })

interface RemoveProps {
  last: boolean
  deviceCount: number | undefined
  onClose: () => void
  onRemoved: (change: CertificateChange) => void
}

/** Destructive action: nothing happens until confirmed; removing the last certificate warns that devices are disabled. */
export function RemoveCertificateDialog(props: RemoveProps & { certificate: SiteCertificate | undefined }) {
  const { certificate, ...rest } = props
  const key = useOpenKey(!!certificate)
  return (
    <Dialog key={key} open={!!certificate} modalType="alert" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface>{certificate && <RemoveCertificateForm certificate={certificate} {...rest} />}</DialogSurface>
    </Dialog>
  )
}

function RemoveCertificateForm(props: {
  certificate: SiteCertificate
  last: boolean
  deviceCount: number | undefined
  onClose: () => void
  onRemoved: (change: CertificateChange) => void
}) {
  const styles = useStyles()
  const remove = useRemoveSiteCertificate(props.certificate.siteId)
  const [error, setError] = useState<string>()
  const site = props.certificate.siteId
  const devices = props.deviceCount === undefined ? 'all devices' : `all ${props.deviceCount} device${props.deviceCount === 1 ? '' : 's'}`

  const confirm = async () => {
    try {
      props.onRemoved(await remove.mutateAsync(props.certificate.fingerprint))
    } catch (e) {
      setError(explain(e))
    }
  }

  return (
    <>
        <DialogBody>
          <DialogTitle>{props.last ? `Remove the last certificate of ${site}?` : 'Remove this certificate?'}</DialogTitle>
          <DialogContent className={styles.content}>
            {error && (
              <MessageBar intent="error" role="alert">
                <MessageBarBody>{error}</MessageBarBody>
              </MessageBar>
            )}
            <Body1>
              Devices whose certificate was issued by <strong>{props.certificate.subject}</strong> will no longer be able to join{' '}
              {site}.
            </Body1>
            {props.last ? (
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
            <Button onClick={props.onClose}>Cancel</Button>
            <Button appearance="primary" onClick={() => void confirm()} disabled={remove.isPending}>
              {remove.isPending ? 'Removing…' : props.last ? 'Remove and disable devices' : 'Remove certificate'}
            </Button>
          </DialogActions>
        </DialogBody>
      </>
  )
}
