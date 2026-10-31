import { Field, Textarea } from '@fluentui/react-components'

/** Comments accepted by the API on reviews and maintenance status changes. */
const MAX_COMMENT = 1000

/** Optional comment of an Editor's decision, with its length. */
export function CommentField({ value, error, onChange }: { value: string; error?: string; onChange: (value: string) => void }) {
  return (
    <Field label="Comment" hint={`Optional · ${value.length}/${MAX_COMMENT} characters`} validationMessage={error}>
      <Textarea resize="vertical" maxLength={MAX_COMMENT} value={value} onChange={(_, data) => onChange(data.value)} />
    </Field>
  )
}
