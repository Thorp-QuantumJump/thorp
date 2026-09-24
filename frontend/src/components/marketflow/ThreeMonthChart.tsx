import { scaleLinear } from '@visx/scale'
import { motion, useReducedMotion } from 'motion/react'
import { useId, useMemo } from 'react'
import { InlineHelp } from '@/components/common/InlineHelp'
import type { IndicatorDisplay } from '@/types/marketFlow'
import { buildAxisDomain, formatObservation, recentDirection } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

/*
  위는 점 위 값 글자, 아래는 달 글자가 들어갈 자리를 둔다. 양옆은 첫·끝 점의 글자가 잘리지 않을 만큼 들인다.
  그림은 카드 폭에 맞춰 줄어들므로(640 → 약 360px) 글자는 viewBox 단위로 크게 잡아야 화면에서 12px 안팎이 된다.
*/
const plot = { width: 640, height: 208, left: 64, right: 590, top: 42, bottom: 150, monthY: 190 }


/**
 * 실제 세 관측점만 직선으로 연결한다. 축 범위는 buildAxisDomain이 정하고 결측 구간은 잇지 않는다.
 *
 * 값과 달(t-2·t-1·t, FR-007)은 점 옆에 바로 적는다. 예전에는 그래프 아래 같은 세 값을 칸 표로 한 번 더
 * 늘어놓아 카드마다 숫자가 두 번씩 읽혔다. 표는 보조기기용으로만 남긴다.
 */
export function ThreeMonthChart({ indicator }: { indicator: IndicatorDisplay }) {
  const reducedMotion = useReducedMotion()
  const descriptionId = useId()
  const chart = useMemo(() => {
    const values = indicator.path.flatMap(({ value }) => value === null ? [] : [value])
    const axis = buildAxisDomain(values)
    const y = scaleLinear({ domain: axis.domain, range: [plot.bottom, plot.top], nice: true })
    const x = (index: number) => plot.left + index * (plot.right - plot.left) / 2
    // M은 새 구간 시작이다. null 양옆의 값을 L로 이어서 보간하지 않는다.
    const path = indicator.path.map((point, index) => {
      if (point.value === null) return ''
      const command = index === 0 || indicator.path[index - 1].value === null ? 'M' : 'L'
      return `${command}${x(index)},${y(point.value)}`
    }).join(' ')
    return { x, y, path, ticks: y.ticks(3), hasValues: values.length > 0, axis }
  }, [indicator.path])
  const axisNote = chart.axis.includesZero
    ? '세로축이 0을 포함합니다.'
    : `세로축은 ${formatObservation(chart.axis.domain[0])}~${formatObservation(chart.axis.domain[1])}${indicator.unit}입니다. 0을 빼고 값에 맞춰 그렸으니 기울기가 실제보다 커 보일 수 있습니다.`

  return (
    <div className={styles.chartBlock} data-reduced-motion={Boolean(reducedMotion)} data-direction={recentDirection(indicator.path)}>
      <div className={styles.chartCaption}>
        <span>최근 3개월 · 단위 {indicator.unit}</span>
        {/* 0을 뺀 축이면 범위를 밝힌다. 밝히지 않으면 작은 변화가 큰 변화처럼 읽힌다. 설명은 물음표 뒤로 내린다. */}
        <InlineHelp label="세로축 범위"><p>{axisNote}</p></InlineHelp>
      </div>
      <p className={styles.srOnly} id={descriptionId}>{axisNote}</p>
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
          <g key={point.month}>
            <circle className={styles.chartPoint} data-month={point.month} cx={chart.x(index)} cy={chart.y(point.value)} r={3.5} />
            <text className={styles.chartValue} x={chart.x(index)} y={chart.y(point.value) - 14} textAnchor="middle">
              {formatObservation(point.value)}
            </text>
          </g>
        ))}
        {indicator.path.map((point, index) => (
          <text key={`month-${point.month}`} className={styles.chartMonth} x={chart.x(index)} y={plot.monthY} textAnchor="middle">
            {point.month.replace('-', '.')}
          </text>
        ))}
      </svg>
      <ol className={styles.srOnly} aria-label={`${indicator.name} 3개월 경로`}>
        {indicator.path.map((point, index) => (
          <li key={point.month}>
            {['t-2', 't-1', 't'][index]} {point.month} {formatObservation(point.value)}{point.value === null ? '' : ` ${indicator.unit}`}
          </li>
        ))}
      </ol>
      {indicator.path.some((point) => point.value === null) ? <p className={styles.chartNote}>자료가 없는 달은 연결하지 않습니다.</p> : null}
    </div>
  )
}
