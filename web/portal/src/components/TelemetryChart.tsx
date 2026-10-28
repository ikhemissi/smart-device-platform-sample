import { LineChart } from 'echarts/charts'
import { AriaComponent, DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import ReactEChartsCore from 'echarts-for-react/esm/core'
import { useMemo, useState } from 'react'
import type { Series } from '../api/types'
import { buildChartOption, seriesKey } from './chartOption'

// Only the parts of ECharts the portal uses are bundled.
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, AriaComponent, CanvasRenderer])

export function TelemetryChart({ series, labels, title }: { series: Series; labels: Record<string, string>; title: string }) {
  const key = seriesKey(series)
  const [rendered, setRendered] = useState<string>()
  const option = useMemo(() => buildChartOption(series, labels), [series, labels])
  // `data-rendered` states whether ECharts has finished drawing the current data (render timing, tests).
  const events = useMemo(() => ({ finished: () => setRendered(key) }), [key])
  return (
    <div role="img" aria-label={title} data-series={key} data-rendered={rendered === key}>
      <ReactEChartsCore echarts={echarts} option={option} notMerge lazyUpdate onEvents={events} style={{ height: 380, width: '100%' }} />
    </div>
  )
}
