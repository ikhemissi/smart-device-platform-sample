import { Caption1, makeStyles } from '@fluentui/react-components'
import { formatAbsolute, formatRelative } from '../format'

const useStyles = makeStyles({ root: { display: 'flex', flexDirection: 'column' } })

/** Relative time with the absolute time underneath. */
export function Timestamp({ value }: { value: string }) {
  const styles = useStyles()
  const absolute = formatAbsolute(value)
  return (
    <time dateTime={value} title={absolute} className={styles.root}>
      <span>{formatRelative(value)}</span>
      <Caption1>{absolute}</Caption1>
    </time>
  )
}
