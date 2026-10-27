import { Button, MessageBar, MessageBarActions, MessageBarBody, MessageBarTitle } from '@fluentui/react-components'
import { explain } from '../api/errors'

/** Non-technical explanation of a failed request with a retry action. */
export function ErrorState({ title, error, onRetry }: { title: string; error: unknown; onRetry: () => void }) {
  return (
    <MessageBar intent="error" layout="multiline" role="alert">
      <MessageBarBody>
        <MessageBarTitle>{title}</MessageBarTitle>
        {explain(error)}
      </MessageBarBody>
      <MessageBarActions>
        <Button onClick={onRetry}>Try again</Button>
      </MessageBarActions>
    </MessageBar>
  )
}
