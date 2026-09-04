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
  initialCapital: number
}

const tooltipDateFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'UTC',
})
const capitalFormatter = new Intl.NumberFormat('ko-KR', {
  style: 'currency', currency: 'KRW', maximumFractionDigits: 0,
})
const returnFormatter = new Intl.NumberFormat('ko-KR', {
  style: 'percent', signDisplay: 'exceptZero', minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

/** 차트의 초기값 100을 기준으로 해당 시점의 모의 수익률과 평가금액을 표시한다. */
function BacktestTooltip({ point, activeStrategies, initialCapital }: {
  point: Record<string, unknown>
  activeStrategies: StrategyId[]
  initialCapital: number
}) {
  const series = [
    ...strategyResults.filter((strategy) => activeStrategies.includes(strategy.id)),
    { id: 'benchmark', name: 'NASDAQ-100', color: 'var(--chart-foreground-muted)' },
  ]

  return (
    <div className={styles.tooltip} role="tooltip">
      <div className={styles.tooltipHeading}>
        <strong>{point.date instanceof Date ? tooltipDateFormatter.format(point.date) : '—'}</strong>
        <span>모의 데이터</span>
      </div>
      <p className={styles.tooltipCaption}>누적수익률 · 평가금액</p>
      {series.map((seriesItem) => {
        const value = point[seriesItem.id]
        const hasValue = typeof value === 'number' && Number.isFinite(value)

        return (
          <div className={styles.tooltipRow} key={seriesItem.id}>
            <span className={styles.tooltipLabel}>
              <i style={{ backgroundColor: seriesItem.color }} aria-hidden="true" />
              {seriesItem.name}
            </span>
            <div className={styles.tooltipValues}>
              <strong>{hasValue ? returnFormatter.format(value / 100 - 1) : '—'}</strong>
              <span>{hasValue ? capitalFormatter.format(initialCapital * value / 100) : '—'}</span>
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** 활성 전략과 NASDAQ-100 벤치마크의 누적 성과를 선 차트로 비교한다. */
export function BacktestChart({
  activeStrategies,
  focusedStrategy,
  status,
  revealSignature,
  initialCapital,
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
        <ChartTooltip
          content={({ point }) => (
            <BacktestTooltip
              point={point}
              activeStrategies={activeStrategies}
              initialCapital={initialCapital}
            />
          )}
        />
      </LineChart>
    </div>
  )
}
