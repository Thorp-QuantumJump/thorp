import { scaleBand, scaleLinear } from '@visx/scale'
import type { DiagramKind, DiagramSeries } from '@/types/learning'
import { estimateLabelWidth, isDrawable, SERIES_STYLES } from './diagramShape'
import styles from './ArticleBody.module.css'

interface HypotheticalDiagramProps {
  /** 이 화면이 모르는 종류면 null이다. 그때는 설명 문장만 보인다 */
  kind: DiagramKind | null
  title: string
  unit: string
  categories: readonly string[]
  series: readonly DiagramSeries[]
  description: string
}

/**
 * 가상 도식(막대·선, 013 FR-005, FR-026, research R38).
 *
 * <p><b>그림은 보조다.</b> 그림은 `aria-hidden`이고 정보의 원본은 그림 아래 설명 문장이다. 수치가 그릴 수
 * 없는 모양이거나 이 화면이 모르는 종류면 설명 문장만 보인다. 실제 과거 자료가 아니므로 '가상의 예' 표시를
 * 붙인다.
 */
export function HypotheticalDiagram({
  kind,
  title,
  unit,
  categories,
  series,
  description,
}: HypotheticalDiagramProps) {
  const drawable = isDrawable(kind, categories, series)

  return (
    <figure className={styles.diagram}>
      <p className={styles.boxHead}>
        <span className={styles.tag}>가상의 예</span>
        {title ? <span className={styles.boxTitle}>{title}</span> : null}
        {unit && drawable ? <span className={styles.unit}>단위: {unit}</span> : null}
      </p>
      {drawable && kind !== null ? (
        <>
          <DiagramPicture kind={kind} categories={categories} series={series} />
          {series.length > 1 ? (
            <ul className={styles.legend} aria-hidden="true">
              {series.map((item, index) => (
                <li key={index}>
                  <svg width="22" height="10" viewBox="0 0 22 10">
                    <line
                      x1="1"
                      x2="21"
                      y1="5"
                      y2="5"
                      className={styles[SERIES_STYLES[index].className]}
                      strokeDasharray={SERIES_STYLES[index].dash}
                    />
                  </svg>
                  {item.name}
                </li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}
      {description ? <figcaption className={styles.boxText}>{description}</figcaption> : null}
    </figure>
  )
}

/** 그림 좌표(viewBox 단위). 카드 폭에 맞춰 줄어든다. */
const frame = { width: 560, top: 16, bottom: 200, left: 48, right: 548, labelGap: 26 }

/** 항목 이름 글자 크기(viewBox 단위). `.diagramLabel`과 같은 값이다. */
const LABEL_FONT = 15

/** 이름이 칸보다 길 때 눕히는 각도. */
const LABEL_ANGLE = 40
const COS = Math.cos((LABEL_ANGLE * Math.PI) / 180)
const SIN = Math.sin((LABEL_ANGLE * Math.PI) / 180)

function bandScale(count: number, left: number) {
  return scaleBand<string>({
    domain: Array.from({ length: count }, (_, index) => String(index)),
    range: [left, frame.right],
    padding: 0.2,
  })
}

function DiagramPicture({
  kind,
  categories,
  series,
}: {
  kind: DiagramKind
  categories: readonly string[]
  series: readonly DiagramSeries[]
}) {
  const values = series.flatMap((item) => item.values)
  // 막대는 0에서 자라야 길이를 비교할 수 있으므로 0을 범위에 넣는다. 선은 오르내림이 보이게 값 범위만 쓴다.
  const low = kind === 'bar' ? Math.min(0, ...values) : Math.min(...values)
  const high = kind === 'bar' ? Math.max(0, ...values) : Math.max(...values)
  const padding = high === low ? Math.abs(high) * 0.1 || 1 : (high - low) * 0.08
  const domainLow = kind === 'bar' ? low : low - padding
  const y = scaleLinear<number>({
    domain: [domainLow, high + padding],
    range: [frame.bottom, frame.top],
  })

  // 항목 이름이 칸보다 길면 비스듬히 눕힌다. 눕힌 이름이 왼쪽과 아래로 뻗는 만큼 여백과 그림 높이를 늘려
  // 겹치거나 잘리지 않게 한다. 서버는 항목 12개, 이름 12자까지 받는다(data-model §5-3).
  const longest = Math.max(...categories.map((category) => estimateLabelWidth(category, LABEL_FONT)))
  const tilted = longest > bandScale(categories.length, frame.left).step() * 0.9
  const left = tilted ? Math.max(frame.left, Math.ceil(longest * COS) + 8) : frame.left
  const x = bandScale(categories.length, left)
  const band = x.bandwidth()
  const barWidth = band / series.length
  const labelY = frame.bottom + frame.labelGap
  const height = tilted ? Math.ceil(labelY + longest * SIN + LABEL_FONT) : labelY + 14

  // 막대의 기준선은 0이다. 음수 막대는 기준선 아래로 자라 부호가 그림에서도 드러난다. 선은 그림 바닥에 기준선을
  // 두고, 값이 0을 가로지르면 0에도 가는 선을 하나 더 긋는다.
  const baselines =
    kind === 'bar'
      ? [y(0)]
      : low < 0 && high > 0
        ? [frame.bottom, y(0)]
        : [frame.bottom]

  return (
    <svg
      className={styles.diagramPicture}
      viewBox={`0 0 ${frame.width} ${height}`}
      aria-hidden="true"
      focusable="false"
    >
      {baselines.map((lineY, index) => (
        <line
          key={index}
          className={styles.diagramAxis}
          x1={left}
          x2={frame.right}
          y1={lineY}
          y2={lineY}
        />
      ))}
      {categories.map((category, index) => {
        const center = (x(String(index)) ?? 0) + band / 2
        return (
          <text
            key={index}
            className={styles.diagramLabel}
            x={center}
            y={labelY}
            textAnchor={tilted ? 'end' : 'middle'}
            transform={tilted ? `rotate(-${LABEL_ANGLE} ${center} ${labelY})` : undefined}
          >
            {category}
          </text>
        )
      })}
      {series.map((item, seriesIndex) => {
        const style = SERIES_STYLES[seriesIndex]
        if (kind === 'bar') {
          return (
            <g key={seriesIndex} className={styles[style.className]}>
              {item.values.map((value, index) => {
                const top = y(Math.max(value, 0))
                const bottom = y(Math.min(value, 0))
                return (
                  <rect
                    key={index}
                    x={(x(String(index)) ?? 0) + seriesIndex * barWidth}
                    y={top}
                    width={Math.max(barWidth - 2, 1)}
                    height={Math.max(bottom - top, 1)}
                    strokeDasharray={style.dash}
                  />
                )
              })}
            </g>
          )
        }
        const points = item.values
          .map((value, index) => `${(x(String(index)) ?? 0) + band / 2},${y(value)}`)
          .join(' ')
        return (
          <polyline
            key={seriesIndex}
            className={styles[style.className]}
            points={points}
            fill="none"
            strokeDasharray={style.dash}
          />
        )
      })}
    </svg>
  )
}
