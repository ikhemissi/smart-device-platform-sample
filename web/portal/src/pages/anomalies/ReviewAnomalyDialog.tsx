import {
  Body1,
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  MessageBar,
  MessageBarBody,
  Radio,
  RadioGroup,
  Textarea,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useReviewAnomaly } from '../../api/queries'
import type { Anomaly, ReviewVerdict } from '../../api/types'
import { metricLabel } from '../../components/chartOption'
import { useOpenKey } from '../../components/useOpenKey'
import { formatAbsolute } from '../../format'
import { VERDICT_LABELS, classificationText } from './anomalyText'

export const MAX_COMMENT = 1000

const useStyles = makeStyles({
  form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
})

interface Props {
  /** The anomaly to review; undefined when closed. */
  anomaly: Anomaly | undefined
  metricName?: (metric: string, unit: string | null) => string
  onClose: () => void
  onReviewed?: (anomaly: Anomaly) => void
}

/** Editors record whether the automated classification is correct, with an optional comment. */
export function ReviewAnomalyDialog(props: Props) {
  const key = useOpenKey(!!props.anomaly)
  return (
    <Dialog key={key} open={!!props.anomaly} modalType="modal" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface aria-describedby={undefined}>{props.anomaly && <ReviewForm {...props} anomaly={props.anomaly} />}</DialogSurface>
    </Dialog>
  )
}

type Errors = Partial<Record<'verdict' | 'comment' | 'form', string>>

function ReviewForm({ anomaly, metricName = metricLabel, onClose, onReviewed }: Props & { anomaly: Anomaly }) {
  const styles = useStyles()
  const review = useReviewAnomaly()
  const [verdict, setVerdict] = useState<ReviewVerdict | ''>(anomaly.review?.verdict ?? '')
  const [comment, setComment] = useState(anomaly.review?.comment ?? '')
  const [errors, setErrors] = useState<Errors>({})

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!verdict) {
      setErrors({ verdict: 'Choose whether the classification is correct.' })
      return
    }
    setErrors({})
    try {
      const reviewed = await review.mutateAsync({ id: anomaly.id, verdict, comment: comment.trim() || null })
      onReviewed?.(reviewed)
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setErrors({ form: 'This anomaly is still being classified. Try again in a few minutes.' })
      } else if (error instanceof ApiError && error.status === 404) {
        setErrors({ form: 'This anomaly no longer exists.' })
      } else if (error instanceof ApiError && error.problem.errors) {
        const fields = error.problem.errors
        setErrors({ verdict: fields.verdict?.[0], comment: fields.comment?.[0], form: explain(error) })
      } else {
        setErrors({ form: explain(error) })
      }
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <DialogBody>
        <DialogTitle>Review this anomaly</DialogTitle>
        <DialogContent className={styles.form}>
          {errors.form && (
            <MessageBar intent="error" role="alert">
              <MessageBarBody>{errors.form}</MessageBarBody>
            </MessageBar>
          )}
          <Body1>
            {metricName(anomaly.metric, anomaly.unit)} of {anomaly.deviceName ?? anomaly.deviceId}, from {formatAbsolute(anomaly.startedAt)}: classified as{' '}
            <strong>{classificationText(anomaly) ?? 'not classified'}</strong>.
          </Body1>
          <Field label="Is the classification correct?" required validationMessage={errors.verdict}>
            <RadioGroup value={verdict} onChange={(_, d) => setVerdict(d.value as ReviewVerdict)}>
              <Radio value="correct" label={VERDICT_LABELS.correct} />
              <Radio value="incorrect" label={VERDICT_LABELS.incorrect} />
            </RadioGroup>
          </Field>
          <Field label="Comment" hint={`Optional · ${comment.length}/${MAX_COMMENT} characters`} validationMessage={errors.comment}>
            <Textarea resize="vertical" maxLength={MAX_COMMENT} value={comment} onChange={(_, d) => setComment(d.value)} />
          </Field>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button appearance="primary" type="submit" disabled={review.isPending}>
            {review.isPending ? 'Saving…' : 'Save review'}
          </Button>
        </DialogActions>
      </DialogBody>
    </form>
  )
}
