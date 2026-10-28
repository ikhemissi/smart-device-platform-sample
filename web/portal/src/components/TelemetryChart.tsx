import { LineChart } from 'echarts/charts'
import { AriaComponent, DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import ReactEChartsCore from 'echarts-for-react/esm/core'
import type { Series } from '../api/types'
import { buildChartOption } from './chartOption'

// Only the parts of ECharts the portal uses are bundled.
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, AriaComponent, CanvasRenderer])

export function TelemetryChart({ series, labels, title }: { series: Series; labels: Record<string, string>; title: string }) {
  return (
    <div role="img" aria-label={title}>
      <ReactEChartsCore
        echarts={echarts}
        option={buildChartOption(series, labels)}
        notMerge
        lazyUpdate
        style={{ height: 380, width: '100%' }}
      />
    </div>
  )
}
