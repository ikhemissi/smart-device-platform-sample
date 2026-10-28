import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Dropdown,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Option,
  Switch,
  Textarea,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { type FormEvent, useState } from 'react'
import { ApiError } from '../../api/client'
import { explain } from '../../api/errors'
import { useSaveThreshold } from '../../api/queries'
import type { ReportedMetric, Threshold } from '../../api/types'
import { metricLabel } from '../../components/chartOption'
import { useOpenKey } from '../../components/useOpenKey'
import { type ThresholdFormErrors, initialValues, validate } from './thresholdForm'

const useStyles = makeStyles({
  form: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
  range: { display: 'flex', gap: tokens.spacingHorizontalM, '& > *': { flex: 1 } },
})

interface Props {
  deviceId: string
  metrics: ReportedMetric[]
  /** The threshold to edit; undefined to create one. */
  threshold?: Threshold
  open: boolean
  onClose: () => void
}

/** Create or edit a threshold (Editors). The API applies the same rules and stays authoritative. */
export function ThresholdDialog(props: Props) {
  const key = useOpenKey(props.open)
  return (
    <Dialog key={key} open={props.open} modalType="modal" onOpenChange={(_, data) => !data.open && props.onClose()}>
      <DialogSurface aria-describedby={undefined}>{props.open && <ThresholdForm {...props} />}</DialogSurface>
    </Dialog>
  )
}

function ThresholdForm({ deviceId, metrics, threshold, onClose }: Props) {
  const styles = useStyles()
  const save = useSaveThreshold(deviceId)
  const [values, setValues] = useState(() => initialValues(threshold, metrics.find((m) => m.metric === 'temperature')?.metric ?? metrics[0]?.metric))
  const [errors, setErrors] = useState<ThresholdFormErrors & { form?: string }>({})
  const unit = metrics.find((m) => m.metric === values.metric)?.unit
  const label = (metric: string) => {
    const m = metrics.find((r) => r.metric === metric)
    return metricLabel(metric, m?.unit, m?.description)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const { input, errors: invalid } = validate(values)
    setErrors(invalid)
    if (!input) return
    try {
      await save.mutateAsync({ existing: threshold, input })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 412) {
        setErrors({ form: 'Someone else changed this threshold. Close this dialog and edit it again.' })
      } else if (error instanceof ApiError && error.problem.errors) {
        const fields = error.problem.errors
        setErrors({ metric: fields.metric?.[0], min: fields.min?.[0], max: fields.max?.[0], recipients: fields.recipients?.[0], form: explain(error) })
      } else {
        setErrors({ form: explain(error) })
      }
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <DialogBody>
        <DialogTitle>{threshold ? 'Edit threshold' : 'Add a threshold'}</DialogTitle>
        <DialogContent className={styles.form}>
          {errors.form && (
            <MessageBar intent="error" role="alert">
              <MessageBarBody>{errors.form}</MessageBarBody>
            </MessageBar>
          )}
          <Field label="Metric" required validationMessage={errors.metric}>
            <Dropdown
              value={values.metric ? label(values.metric) : ''}
              selectedOptions={values.metric ? [values.metric] : []}
              onOptionSelect={(_, d) => setValues({ ...values, metric: d.optionValue ?? '' })}
            >
              {metrics.map((m) => (
                <Option key={m.metric} value={m.metric}>
                  {label(m.metric)}
                </Option>
              ))}
            </Dropdown>
          </Field>
          <div className={styles.range}>
            <Field label={`Minimum${unit ? ` (${unit})` : ''}`} validationMessage={errors.min} hint="Optional">
              <Input inputMode="decimal" value={values.min} onChange={(_, d) => setValues({ ...values, min: d.value })} />
            </Field>
            <Field label={`Maximum${unit ? ` (${unit})` : ''}`} validationMessage={errors.max} hint="Optional">
              <Input inputMode="decimal" value={values.max} onChange={(_, d) => setValues({ ...values, max: d.value })} />
            </Field>
          </div>
          <Field label="Email recipients" required validationMessage={errors.recipients} hint="1 to 10 addresses, one per line or separated by commas.">
            <Textarea resize="vertical" value={values.recipients} onChange={(_, d) => setValues({ ...values, recipients: d.value })} />
          </Field>
          <Switch
            label={values.enabled ? 'Enabled: readings outside the range open an alert' : 'Disabled: no alert is raised'}
            checked={values.enabled}
            onChange={(_, d) => setValues({ ...values, enabled: d.checked })}
          />
          {threshold?.openAlertId && !values.enabled && (
            <MessageBar intent="info">
              <MessageBarBody>Disabling the threshold closes its open alert without sending an email.</MessageBarBody>
            </MessageBar>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button appearance="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : threshold ? 'Save threshold' : 'Add threshold'}
          </Button>
        </DialogActions>
      </DialogBody>
    </form>
  )
}
