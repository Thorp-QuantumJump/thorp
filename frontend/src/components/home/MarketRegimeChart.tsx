import { scaleLinear } from '@visx/scale'
import { curveMonotoneX, line } from 'd3-shape'
import { motion, useReducedMotion } from 'motion/react'
import type { MarketCyclePoint, MarketOverviewChart } from '@/types/market'
import styles from './MarketRegimeChart.module.css'

interface MarketRegimeChartProps {
  chart: MarketOverviewChart
}

const chartMargin = { top: 34, right: 36, bottom: 44, left: 54 }
const chartWidth = 600
const chartHeight = 310
const xTickYears = [2018, 2020, 2022, 2024, 2026]

/** 월 문자열을 시간 축에서 비교할 수 있는 UTC 타임스탬프로 변환한다. */
function toTimestamp(date: string) {
  return Date.parse(`${date}-01T00:00:00Z`)
}

/** 지표별 자릿수를 유지하면서 축과 현재 값을 짧은 숫자로 표현한다. */
function formatChartValue(value: number, decimals = 0) {
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(value)
}

/** 같은 연도 축 위에 선택된 시장 지표의 장기 흐름을 단일선으로 그린다. */
export function MarketRegimeChart({ chart }: MarketRegimeChartProps) {
  const prefersReducedMotion = useReducedMotion()
  const firstPoint = chart.series[0]
  const latestPoint = chart.series.at(-1)

  if (!firstPoint || !latestPoint) return null

  const xScale = scaleLinear({
    domain: [toTimestamp(firstPoint.date), toTimestamp(latestPoint.date)],
    range: [chartMargin.left, chartWidth - chartMargin.right],
  })
  const yScale = scaleLinear({
    domain: chart.yDomain,
    range: [chartHeight - chartMargin.bottom, chartMargin.top],
  })
  const path = line<MarketCyclePoint>()
    .x((point) => xScale(toTimestamp(point.date)))
    .y((point) => yScale(point.value))
    .curve(curveMonotoneX)(chart.series)
  const latestX = xScale(toTimestamp(latestPoint.date))
  const latestY = yScale(latestPoint.value)

  return (
    <div className={styles.container}>
      <svg
        className={styles.chart}
        viewBox={`0 0 ${chartWidth} ${chartHeight}`}
        role="img"
        aria-labelledby={`chart-title-${chart.id} chart-description-${chart.id}`}
      >
        <title id={`chart-title-${chart.id}`}>최근 10년 {chart.name}</title>
        <desc id={`chart-description-${chart.id}`}>
          2016년부터 2026년까지 {chart.name}의 예시 흐름과 현재 값{' '}
          {formatChartValue(latestPoint.value, chart.valueDecimals)}을 표시합니다.
        </desc>

        <motion.g
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: prefersReducedMotion ? 0 : 0.42 }}
        >
          {chart.yTicks.map((tick) => {
            const y = yScale(tick)
            const isReference = tick === chart.referenceValue

            return (
              <g key={tick}>
                <line
                  className={isReference ? styles.referenceLine : styles.gridLine}
                  x1={chartMargin.left}
                  x2={chartWidth - chartMargin.right}
                  y1={y}
                  y2={y}
                />
                <text className={styles.yTick} textAnchor="end" x={chartMargin.left - 12} y={y + 4}>
                  {formatChartValue(tick)}
                </text>
              </g>
            )
          })}

          {xTickYears.map((year) => {
            const x = xScale(Date.parse(`${year}-01-01T00:00:00Z`))

            return (
              <g key={year}>
                <line
                  className={styles.xTickLine}
                  x1={x}
                  x2={x}
                  y1={chartHeight - chartMargin.bottom}
                  y2={chartHeight - chartMargin.bottom + 6}
                />
                <text
                  className={styles.xTick}
                  textAnchor="middle"
                  x={x}
                  y={chartHeight - 17}
                >
                  {year}
                </text>
              </g>
            )
          })}
        </motion.g>

        {path && (
          <motion.path
            className={styles.cycleLine}
            d={path}
            fill="none"
            initial={{ pathLength: 0, opacity: 0 }}
            animate={{ pathLength: 1, opacity: 1 }}
            transition={{
              delay: prefersReducedMotion ? 0 : 0.2,
              duration: prefersReducedMotion ? 0 : 1.65,
              ease: [0.22, 1, 0.36, 1],
            }}
          />
        )}

        <motion.g
          initial={{ opacity: 0, scale: 0.6 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{
            delay: prefersReducedMotion ? 0 : 1.72,
            duration: prefersReducedMotion ? 0 : 0.35,
            ease: [0.22, 1, 0.36, 1],
          }}
          style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
        >
          <circle className={styles.latestHalo} cx={latestX} cy={latestY} r={9} />
          <circle className={styles.latestPoint} cx={latestX} cy={latestY} r={4.5} />
          <text className={styles.latestValue} textAnchor="end" x={latestX - 8} y={latestY - 12}>
            {formatChartValue(latestPoint.value, chart.valueDecimals)}
          </text>
        </motion.g>

        <text className={styles.indexLabel} x={chartMargin.left} y={chartMargin.top - 13}>
          {chart.axisLabel}
        </text>
      </svg>
    </div>
  )
}
