import { Grid } from '@/components/charts/grid'
import { Line, LineChart } from '@/components/charts/line-chart'
import { ChartTooltip } from '@/components/charts/tooltip'
import { XAxis } from '@/components/charts/x-axis'
import {
  backtestChartData,
  strategyResults,
  type StrategyId,
} from '@/data/backtestMock'
import styles from './BacktestChart.module.css'

interface BacktestChartProps {
  activeStrategies: StrategyId[]
  focusedStrategy: StrategyId | null
  status: 'loading' | 'ready'
  revealSignature: string
}

/** 활성 전략과 NASDAQ-100 벤치마크의 누적 성과를 선 차트로 비교한다. */
export function BacktestChart({
  activeStrategies,
  focusedStrategy,
  status,
  revealSignature,
}: BacktestChartProps) {
  return (
    <div className={styles.chart}>
      <LineChart
        data={backtestChartData}
        xDataKey="date"
        status={status}
        loadingLabel="전략을 계산하고 있습니다"
        animationDuration={1400}
        revealSignature={revealSignature}
        aspectRatio="2 / 1"
        margin={{ top: 24, right: 24, bottom: 42, left: 24 }}
      >
        <Grid horizontal numTicksRows={5} strokeDasharray="2,5" />

        {/* 벤치마크는 전략 성과를 판단하는 공통 기준선으로 유지한다. */}
        <Line
          dataKey="benchmark"
          stroke="#9aa3ad"
          strokeWidth={1.6}
          animate
          fadeEdges={false}
          showHighlight={false}
        />

        {/* 강조 전략이 있으면 나머지 전략의 색을 낮춰 비교 대상을 선명하게 한다. */}
        {strategyResults.map((strategy) =>
          activeStrategies.includes(strategy.id) ? (
            <Line
              key={strategy.id}
              dataKey={strategy.id}
              stroke={
                focusedStrategy && focusedStrategy !== strategy.id
                  ? strategy.mutedColor
                  : strategy.color
              }
              strokeWidth={focusedStrategy === strategy.id ? 3.8 : 2.5}
              animate
              fadeEdges={false}
            />
          ) : null,
        )}

        <XAxis numTicks={5} tickMode="domain" />
        <ChartTooltip />
      </LineChart>
    </div>
  )
}
