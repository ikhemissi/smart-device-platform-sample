import { Dialog, DialogSurface } from '@fluentui/react-components'
import type { ReactNode } from 'react'
import { useOpenKey } from './useOpenKey'

/**
 * Fluent dialog that stays mounted and is driven by `open`, so closing it never unmounts an open modal (which would leave
 * the rest of the portal hidden from assistive technology). Render the dialog's content only while it is open.
 *
 * Form dialogs (`modal`) have no single describing element; confirmations (`alert`) are described by their content.
 */
export function ModalDialog({
  open,
  onClose,
  modalType = 'modal',
  children,
}: {
  open: boolean
  onClose: () => void
  modalType?: 'modal' | 'alert'
  children: ReactNode
}) {
  const key = useOpenKey(open)
  const surfaceProps = modalType === 'modal' ? { 'aria-describedby': undefined } : {}
  return (
    <Dialog key={key} open={open} modalType={modalType} onOpenChange={(_, data) => !data.open && onClose()}>
      <DialogSurface {...surfaceProps}>{children}</DialogSurface>
    </Dialog>
  )
}
