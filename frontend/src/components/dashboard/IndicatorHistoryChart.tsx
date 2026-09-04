import { scaleLinear } from '@visx/scale'
import { curveMonotoneX, line } from 'd3-shape'
import { motion, useReducedMotion } from 'motion/react'
import type { Indicator, IndicatorSeriesPoint } from '@/types/dashboard'
import { selectIndicatorPeriod } from '@/utils/indicatorHistory'
import styles from './IndicatorHistoryCard.module.css'

const width = 440
const height = 230
const margin = { left: 58, right: 18, top: 22, bottom: 32 }

/** 일간·월간 날짜를 같은 UTC 시간축에 배치한다. */
function timestamp(date: string) {
  return Date.parse(`${date.length === 7 ? `${date}-01` : date}T00:00:00Z`)
}

/** 확대 카드의 5년 이력을 실제 날짜 간격과 지표 고유 단위로 그린다. */
export function IndicatorHistoryChart({ indicator }: { indicator: Indicator }) {
  const reducedMotion = useReducedMotion()
  const series = selectIndicatorPeriod(indicator.series, indicator.observationDate, 60)
  const first = series[0]
  const last = series.at(-1)
  if (!first || !last || series.length < 2) {
    return <p className={styles.empty}>장기 추이를 표시할 데이터가 부족합니다.</p>
  }

  const values = series.map((point) => point.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const padding = (max - min) * 0.15 || Math.max(Math.abs(max) * 0.05, 1)
  const xScale = scaleLinear({
    domain: [timestamp(first.date), timestamp(last.date)],
    range: [margin.left, width - margin.right],
  })
  const yScale = scaleLinear({
    domain: [min - padding, max + padding],
    range: [height - margin.bottom, margin.top],
    nice: true,
  })
  const path = line<IndicatorSeriesPoint>()
    .x((point) => xScale(timestamp(point.date)))
    .y((point) => yScale(point.value))
    .curve(curveMonotoneX)(series)
  const ticks = [first, series[Math.floor((series.length - 1) / 2)], last]
    .filter((point, index, points) => points.findIndex((item) => item.date === point.date) === index)
  const formatValue = (value: number) => value.toLocaleString('ko-KR', {
    maximumFractionDigits: indicator.decimals,
  })

  return (
    <svg
      className={styles.historyChart}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={`${indicator.name} 장기 추이, ${first.date}부터 ${last.date}까지, 최근 값 ${formatValue(last.value)} ${indicator.unit}`}
    >
      {yScale.ticks(4).map((value) => (
        <g key={value}>
          <line
            className={styles.gridLine}
            x1={margin.left}
            x2={width - margin.right}
            y1={yScale(value)}
            y2={yScale(value)}
          />
          <text x={margin.left - 9} y={yScale(value) + 4} textAnchor="end">{formatValue(value)}</text>
        </g>
      ))}
      <text x={margin.left} y={13}>{indicator.unit}</text>
      {ticks.map((point, index) => (
        <text
          key={point.date}
          x={xScale(timestamp(point.date))}
          y={height - 8}
          textAnchor={index === 0 ? 'start' : index === ticks.length - 1 ? 'end' : 'middle'}
        >
          {point.date.slice(0, 7).replace('-', '.')}
        </text>
      ))}
      {path && (
        <motion.path
          className={styles.historyLine}
          d={path}
          fill="none"
          initial={reducedMotion ? false : { pathLength: 0 }}
          animate={{ pathLength: 1 }}
          transition={{
            delay: reducedMotion ? 0 : 0.3,
            duration: reducedMotion ? 0 : 0.95,
            ease: [0.22, 1, 0.36, 1],
          }}
        />
      )}
      <motion.circle
        className={styles.latestPoint}
        cx={xScale(timestamp(last.date))}
        cy={yScale(last.value)}
        r={4}
        initial={reducedMotion ? false : { opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: reducedMotion ? 0 : 1.2, duration: reducedMotion ? 0 : 0.18 }}
      />
    </svg>
  )
}
