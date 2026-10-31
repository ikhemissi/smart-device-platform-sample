import { MessageBar, MessageBarBody } from '@fluentui/react-components'

/** Why a dialog's action failed, announced to assistive technology; nothing when there is no error. */
export function FormError({ message }: { message: string | undefined }) {
  if (!message) {
    return null
  }
  return (
    <MessageBar intent="error" role="alert">
      <MessageBarBody>{message}</MessageBarBody>
    </MessageBar>
  )
}
