import { init, use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, MarkLineComponent, MarkPointComponent, MarkAreaComponent, DataZoomComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, GridComponent, LegendComponent, TooltipComponent, MarkLineComponent, MarkPointComponent, MarkAreaComponent, DataZoomComponent, CanvasRenderer])

export { init }
