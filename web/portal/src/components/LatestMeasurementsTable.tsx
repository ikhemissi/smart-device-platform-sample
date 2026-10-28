import {
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { useEffect, useRef, useState } from 'react'
import type { Measurement, ReportedMetric } from '../api/types'
import { Timestamp } from './Timestamp'

const HIGHLIGHT_MS = 4000

const useStyles = makeStyles({
  fresh: {
    animationName: { from: { backgroundColor: tokens.colorPaletteYellowBackground2 }, to: { backgroundColor: 'transparent' } },
    animationDuration: `${HIGHLIGHT_MS}ms`,
    animationTimingFunction: 'ease-out',
  },
  number: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
})

/** Order of columns: metrics as reported (known metrics first, in their API order), then any other keys. */
function columns(measurements: Measurement[], metrics: ReportedMetric[]): string[] {
  const present = new Set(measurements.flatMap((m) => Object.keys(m.metrics)))
  const known = metrics.map((m) => m.metric).filter((m) => present.has(m))
  return [...known, ...[...present].filter((m) => !known.includes(m)).sort()]
}

const key = (m: Measurement) => m.measuredAt

/** Latest measurements, most recent first; rows that appear after a refresh are briefly highlighted. */
export function LatestMeasurementsTable({ measurements, metrics }: { measurements: Measurement[]; metrics: ReportedMetric[] }) {
  const styles = useStyles()
  const seen = useRef<Set<string> | null>(null)
  const [fresh, setFresh] = useState<Set<string>>(new Set())

  useEffect(() => {
    const keys = measurements.map(key)
    if (seen.current !== null) {
      const added = keys.filter((k) => !seen.current!.has(k))
      if (added.length > 0) {
        setFresh(new Set(added))
        const timer = setTimeout(() => setFresh(new Set()), HIGHLIGHT_MS)
        seen.current = new Set(keys)
        return () => clearTimeout(timer)
      }
    }
    seen.current = new Set(keys)
  }, [measurements])

  const unit = (metric: string) => metrics.find((m) => m.metric === metric)?.unit
  const cols = columns(measurements, metrics)
  return (
    <Table aria-label="Latest measurements" size="small">
      <TableHeader>
        <TableRow>
          <TableHeaderCell>Measured</TableHeaderCell>
          {cols.map((c) => (
            <TableHeaderCell key={c}>
              {c}
              {unit(c) ? ` (${unit(c)})` : ''}
            </TableHeaderCell>
          ))}
          <TableHeaderCell>Format</TableHeaderCell>
        </TableRow>
      </TableHeader>
      <TableBody>
        {measurements.map((m) => (
          <TableRow key={key(m)} className={fresh.has(key(m)) ? styles.fresh : undefined} data-fresh={fresh.has(key(m)) || undefined}>
            <TableCell>
              <Timestamp value={m.measuredAt} />
            </TableCell>
            {cols.map((c) => (
              <TableCell key={c} className={styles.number}>
                {m.metrics[c] === undefined ? '–' : String(m.metrics[c])}
              </TableCell>
            ))}
            <TableCell>{m.payloadFormat}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
