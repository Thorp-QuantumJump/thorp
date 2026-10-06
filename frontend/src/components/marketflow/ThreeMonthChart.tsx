import { scaleLinear } from '@visx/scale'
import { motion, useReducedMotion } from 'motion/react'
import { useId, useMemo } from 'react'
import type { IndicatorDisplay } from '@/types/marketFlow'
import { buildAxisDomain, describeAxis, formatObservation, recentDirection } from './marketFlowFormat'
import type { PastPath } from './pastOverlay'
import styles from './MarketFlow.module.css'

/*
  위는 점 위 값 글자, 아래는 달 글자가 들어갈 자리를 둔다. 양옆은 첫·끝 점의 글자가 잘리지 않을 만큼 들인다.
  그림은 카드 폭에 맞춰 줄어들므로(640 → 약 360px) 글자는 viewBox 단위로 크게 잡아야 화면에서 12px 안팎이 된다.
  당시 경로를 겹치면 달 글자 밑에 당시 달 한 줄이 더 서고, 점 아래로 내려간 값 글자가 달 글자와 겹치지 않게
  달 줄을 조금 내린다.
*/
const plot = { width: 640, height: 208, left: 64, right: 620, inset: 48, top: 42, bottom: 150, monthY: 190 }
const pastPlot = { ...plot, height: 240, monthY: 206, pastMonthY: 230 }

const ORDER = ['t-2', 't-1', 't'] as const

/**
 * 실제 세 관측점만 직선으로 연결한다. 축 범위는 buildAxisDomain이 정하고 결측 구간은 잇지 않는다.
 *
 * 값과 달(t-2·t-1·t, FR-007)은 점 옆에 바로 적는다. 예전에는 그래프 아래 같은 세 값을 칸 표로 한 번 더
 * 늘어놓아 카드마다 숫자가 두 번씩 읽혔다. 표는 보조기기용으로만 남긴다.
 *
 * `past`가 있으면 고른 과거 구간의 같은 지표 경로를 점선으로 겹친다(009 FR-019). 값은 010이 비교한 달끼리만
 * 맞대므로 자리마다 비어 있을 수 있고, 빈 자리는 잇지 않는다. `band`는 010 공통 척도의 보통 범위 띠다.
 * `comparing`이면 당시 경로를 받는 중이어도 당시 달 줄 자리를 비워 둔다. 사례를 바꿀 때 그래프 높이가 줄었다
 * 늘지 않는다.
 */
export function ThreeMonthChart({ indicator, past = null, band = null, comparing = false }: {
  indicator: IndicatorDisplay
  past?: PastPath | null
  band?: [number, number] | null
  comparing?: boolean
}) {
  const reducedMotion = useReducedMotion()
  const descriptionId = useId()
  const frame = past || comparing ? pastPlot : plot
  const extraValues = useMemo(() => [
    ...(past?.values.flatMap((value) => (value === null ? [] : [value])) ?? []),
    ...(band ?? []),
  ], [past, band])
  const chart = useMemo(() => {
    const nowValues = indicator.path.flatMap(({ value }) => value === null ? [] : [value])
    const values = [...nowValues, ...extraValues]
    const axis = buildAxisDomain(values)
    const y = scaleLinear({ domain: axis.domain, range: [plot.bottom, plot.top], nice: true })
    // 첫·끝 점은 가장자리에서 들인다. 붙여 두면 첫 점의 값 글자가 세로축 눈금 글자와 겹친다.
    const x = (index: number) => plot.left + plot.inset + index * (plot.right - plot.left - plot.inset * 2) / 2
    // M은 새 구간 시작이다. null 양옆의 값을 L로 이어서 보간하지 않는다.
    const line = (series: readonly (number | null)[]) => series.map((value, index) => {
      if (value === null) return ''
      const command = index === 0 || series[index - 1] === null ? 'M' : 'L'
      return `${command}${x(index)},${y(value)}`
    }).join(' ')
    return {
      x,
      y,
      path: line(indicator.path.map((point) => point.value)),
      pastPath: past ? line(past.values) : '',
      ticks: y.ticks(3),
      // '관측값 없음'은 지금 값으로만 판단한다. 당시 값이나 보통 범위가 있어도 지금 경로가 없으면 그렇게 적는다.
      hasValues: nowValues.length > 0,
    }
  }, [indicator.path, extraValues, past])
  const axisNote = describeAxis(indicator, extraValues)
  const pastValue = (index: number) => past?.values[index] ?? null

  return (
    <div className={styles.chartBlock} data-reduced-motion={Boolean(reducedMotion)} data-direction={recentDirection(indicator.path)}>
      <div className={styles.chartCaption}>
        <span>최근 3개월 · 단위 {indicator.unit}</span>
      </div>
      <p className={styles.srOnly} id={descriptionId}>{axisNote}</p>
      <svg
        viewBox={`0 0 ${frame.width} ${frame.height}`}
        className={styles.chart}
        role="img"
        aria-label={`${indicator.name} 3개월 선 그래프`}
        aria-describedby={descriptionId}
      >
        <title>
          {indicator.name}: {indicator.path.map((point) => `${point.month} ${formatObservation(point.value, indicator.unit)}${point.value === null ? '' : ` ${indicator.unit}`}`).join(', ')}
          {past ? ` / 당시 ${past.values.map((value, index) => `${past.months[index] ?? '비교 안 함'} ${formatObservation(value, indicator.unit)}`).join(', ')}` : ''}
        </title>
        {band ? (
          <rect
            className={styles.chartBand}
            data-band="true"
            x={plot.left}
            width={plot.right - plot.left}
            y={chart.y(band[1])}
            height={Math.max(0, chart.y(band[0]) - chart.y(band[1]))}
          />
        ) : null}
        {chart.ticks.map((tick) => (
          <g key={tick} className={styles.chartGrid}>
            <line x1={plot.left} x2={plot.right} y1={chart.y(tick)} y2={chart.y(tick)} />
            <text x={plot.left - 10} y={chart.y(tick)} dy=".35em" textAnchor="end">
              {new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(tick)}
            </text>
          </g>
        ))}
        {past && chart.pastPath ? (
          // 당시 선은 그려 나가지 않고 서서히 나타난다. pathLength 움직임은 선의 점선 간격을 덮어써 실선으로 만든다.
          <motion.path
            key={past.months.join()}
            className={styles.chartPastLine}
            data-past="true"
            d={chart.pastPath}
            initial={reducedMotion ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: reducedMotion ? 0 : 0.35, ease: 'easeOut' }}
          />
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
        {past ? past.values.map((value, index) => value === null ? null : (
          <g key={`past-${index}`}>
            <circle className={styles.chartPastPoint} cx={chart.x(index)} cy={chart.y(value)} r={3.2} />
            {/* 당시 값은 점 옆에 작게 적는다. 지금 값보다 위에 있으면 위로, 아래면 아래로 비켜 서로 겹치지 않는다. 끝 점은 왼쪽에 적는다. */}
            <text
              className={styles.chartPastValue}
              x={index === 2 ? chart.x(index) - 12 : chart.x(index) + 12}
              y={chart.y(value) + (isAbove(value, indicator.path[index].value) ? -10 : 24)}
              textAnchor={index === 2 ? 'end' : 'start'}
            >
              {formatObservation(value, indicator.unit)}
            </text>
          </g>
        )) : null}
        {indicator.path.map((point, index) => point.value === null ? null : (
          <g key={point.month}>
            <circle className={styles.chartPoint} data-month={point.month} cx={chart.x(index)} cy={chart.y(point.value)} r={3.5} />
            <text
              className={styles.chartValue}
              x={chart.x(index)}
              // 당시 값이 위에 있으면 지금 값 글자는 점 아래로 내린다.
              y={chart.y(point.value) + (isAbove(pastValue(index), point.value) ? 32 : -14)}
              textAnchor="middle"
            >
              {formatObservation(point.value, indicator.unit)}
            </text>
          </g>
        ))}
        {indicator.path.map((point, index) => (
          <text key={`month-${point.month}`} className={styles.chartMonth} x={chart.x(index)} y={frame.monthY} textAnchor="middle">
            {point.month.replace('-', '.')}
          </text>
        ))}
        {past ? past.months.map((month, index) => month === null ? null : (
          <text key={`past-month-${index}`} className={styles.chartPastMonth} x={chart.x(index)} y={pastPlot.pastMonthY} textAnchor="middle">
            {month.replace('-', '.')}
          </text>
        )) : null}
      </svg>
      <ol className={styles.srOnly} aria-label={`${indicator.name} 3개월 경로`}>
        {indicator.path.map((point, index) => (
          <li key={point.month}>
            {ORDER[index]} {point.month} {formatObservation(point.value, indicator.unit)}{point.value === null ? '' : ` ${indicator.unit}`}
            {past && past.months[index] ? ` · 당시 ${past.months[index]} ${formatObservation(pastValue(index), indicator.unit)}${pastValue(index) === null ? '' : ` ${indicator.unit}`}` : ''}
          </li>
        ))}
      </ol>
      {indicator.path.some((point) => point.value === null) ? <p className={styles.chartNote}>자료가 없는 달은 연결하지 않습니다.</p> : null}
    </div>
  )
}

/** 앞 값이 뒤 값보다 위(더 큰 값)에 있는지. 어느 쪽이든 없으면 비켜 설 일이 없다. */
function isAbove(value: number | null, other: number | null): boolean {
  return value !== null && other !== null && value > other
}
