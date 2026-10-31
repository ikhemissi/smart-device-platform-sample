import { Dropdown, Field, Option } from '@fluentui/react-components'

/**
 * Multi-select status filter of a list page. `onChange` receives the `status` URL parameter: the selected statuses,
 * comma-separated, or empty when they are the default ones. At least one status stays selected.
 */
export function StatusFilter<T extends string>({
  statuses,
  labels,
  selected,
  defaults,
  className,
  onChange,
}: {
  /** Every status, in the order offered. */
  statuses: readonly T[]
  labels: Record<T, string>
  selected: T[]
  defaults: T[]
  className?: string
  onChange: (param: string) => void
}) {
  return (
    <Field label="Status" className={className}>
      <Dropdown
        multiselect
        selectedOptions={selected}
        value={selected.map((status) => labels[status]).join(', ')}
        onOptionSelect={(_, data) => {
          const next = statuses.filter((status) => data.selectedOptions.includes(status))
          if (next.length > 0) {
            onChange(next.join(',') === defaults.join(',') ? '' : next.join(','))
          }
        }}
      >
        {statuses.map((status) => (
          <Option key={status} value={status}>
            {labels[status]}
          </Option>
        ))}
      </Dropdown>
    </Field>
  )
}
