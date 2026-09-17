import { scaleLinear } from '@visx/scale'
import { motion, useReducedMotion } from 'motion/react'
import { useId, useMemo, useState } from 'react'
import type { IndicatorDisplay } from '@/types/marketFlow'
import { formatObservation } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

const plot = { width: 640, height: 172, left: 58, right: 622, top: 14, bottom: 146 }

/** 실제 세 관측점만 직선으로 연결한다. 0을 포함한 축, 결측 구간의 단절을 유지한다. */
export function ThreeMonthChart({ indicator }: { indicator: IndicatorDisplay }) {
  const reducedMotion = useReducedMotion()
  const descriptionId = useId()
  const [activePoint, setActivePoint] = useState<number | null>(null)
  const chart = useMemo(() => {
    const values = indicator.path.flatMap(({ value }) => value === null ? [] : [value])
    const min = Math.min(0, ...values)
    const max = Math.max(0, ...values)
    const y = scaleLinear({ domain: min === max ? [0, 1] : [min, max], range: [plot.bottom, plot.top], nice: true })
    const x = (index: number) => plot.left + index * (plot.right - plot.left) / 2
    // M은 새 구간 시작이다. null 양옆의 값을 L로 이어서 보간하지 않는다.
    const path = indicator.path.map((point, index) => {
      if (point.value === null) return ''
      const command = index === 0 || indicator.path[index - 1].value === null ? 'M' : 'L'
      return `${command}${x(index)},${y(point.value)}`
    }).join(' ')
    return { x, y, path, ticks: y.ticks(3), hasValues: values.length > 0 }
  }, [indicator.path])

  return (
    <div className={styles.chartBlock} data-reduced-motion={Boolean(reducedMotion)}>
      <div className={styles.chartCaption} id={descriptionId}>
        <span>최근 3개월 경로</span><span>0을 포함한 축 · 단위 {indicator.unit}</span>
      </div>
      <svg
        viewBox={`0 0 ${plot.width} ${plot.height}`}
        className={styles.chart}
        role="img"
        aria-label={`${indicator.name} 3개월 선 그래프`}
        aria-describedby={descriptionId}
      >
        <title>{indicator.name}: {indicator.path.map((point) => `${point.month} ${formatObservation(point.value)}${point.value === null ? '' : ` ${indicator.unit}`}`).join(', ')}</title>
        {chart.ticks.map((tick) => (
          <g key={tick} className={styles.chartGrid}>
            <line x1={plot.left} x2={plot.right} y1={chart.y(tick)} y2={chart.y(tick)} />
            <text x={plot.left - 10} y={chart.y(tick)} dy=".35em" textAnchor="end">
              {new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 2 }).format(tick)}
            </text>
          </g>
        ))}
        {activePoint !== null ? (
          <line className={styles.crosshair} x1={chart.x(activePoint)} x2={chart.x(activePoint)} y1={plot.top} y2={plot.bottom} />
        ) : null}
        {chart.hasValues ? (
          <motion.path
            className={styles.chartLine}
            d={chart.path}
            initial={reducedMotion ? false : { pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: reducedMotion ? 0 : 0.55, ease: 'easeOut' }}
          />
        ) : <text className={styles.chartEmpty} x="50%" y="50%" textAnchor="middle">관측값 없음</text>}
        {indicator.path.map((point, index) => point.value === null ? null : (
          <circle
            key={point.month}
            className={styles.chartPoint}
            data-month={point.month}
            cx={chart.x(index)} cy={chart.y(point.value)} r={activePoint === index ? 5 : 3.5}
          />
        ))}
      </svg>
      <ol className={styles.path} aria-label={`${indicator.name} 3개월 경로`}>
        {indicator.path.map((point, index) => (
          <li key={point.month}>
            <button
              type="button"
              aria-label={`${point.month}, ${formatObservation(point.value)}${point.value === null ? '' : ` ${indicator.unit}`}`}
              onMouseEnter={() => setActivePoint(index)} onMouseLeave={() => setActivePoint(null)}
              onFocus={() => setActivePoint(index)} onBlur={() => setActivePoint(null)}
              className={styles.pathPoint}
            >
              <span className={styles.pathMonth}>{point.month}<small>{['t-2', 't-1', 't'][index]}</small></span>
              <strong>{formatObservation(point.value)}{point.value === null ? null : <small> {indicator.unit}</small>}</strong>
            </button>
          </li>
        ))}
      </ol>
      {indicator.path.some((point) => point.value === null) ? <p className={styles.chartNote}>자료가 없는 달은 연결하지 않습니다.</p> : null}
    </div>
  )
}
