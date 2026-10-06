import { scaleBand, scaleLinear } from '@visx/scale'
import type { DiagramSeries } from '@/types/learning'
import { isDrawable, SERIES_STYLES } from './diagramShape'
import styles from './ArticleBody.module.css'

interface HypotheticalDiagramProps {
  kind: 'bar' | 'line'
  title: string
  unit: string
  categories: readonly string[]
  series: readonly DiagramSeries[]
  description: string
}

/** 그림 좌표. 카드 폭에 맞춰 줄어들므로 글자는 viewBox 단위로 크게 잡는다. */
const frame = { width: 560, height: 240, left: 48, right: 548, top: 16, bottom: 200, labelY: 226 }

/**
 * 가상 도식(막대·선, 013 FR-005, FR-026, research R38).
 *
 * <p><b>그림은 보조다.</b> 그림은 `aria-hidden`이고 정보의 원본은 그림 아래 설명 문장이다. 수치가 그릴 수
 * 없는 모양이면 설명 문장만 보인다. 실제 과거 자료가 아니므로 '가상의 예' 표시를 붙인다.
 */
export function HypotheticalDiagram({
  kind,
  title,
  unit,
  categories,
  series,
  description,
}: HypotheticalDiagramProps) {
  const drawable = isDrawable(categories, series)

  return (
    <figure className={styles.diagram}>
      <p className={styles.boxHead}>
        <span className={styles.tag}>가상의 예</span>
        {title ? <span className={styles.boxTitle}>{title}</span> : null}
        {unit && drawable ? <span className={styles.unit}>단위: {unit}</span> : null}
      </p>
      {drawable ? (
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

function DiagramPicture({
  kind,
  categories,
  series,
}: Pick<HypotheticalDiagramProps, 'kind' | 'categories' | 'series'>) {
  const values = series.flatMap((item) => item.values)
  // 막대는 0에서 자라야 길이를 비교할 수 있으므로 0을 범위에 넣는다. 선은 오르내림이 보이게 값 범위만 쓴다.
  const low = kind === 'bar' ? Math.min(0, ...values) : Math.min(...values)
  const high = kind === 'bar' ? Math.max(0, ...values) : Math.max(...values)
  const padding = high === low ? Math.abs(high) * 0.1 || 1 : (high - low) * 0.08
  const y = scaleLinear<number>({
    domain: [kind === 'bar' ? low : low - padding, high + padding],
    range: [frame.bottom, frame.top],
  })
  const x = scaleBand<string>({
    domain: categories.map((_, index) => String(index)),
    range: [frame.left, frame.right],
    padding: 0.2,
  })
  const band = x.bandwidth()
  const barWidth = band / series.length

  return (
    <svg
      className={styles.diagramPicture}
      viewBox={`0 0 ${frame.width} ${frame.height}`}
      aria-hidden="true"
      focusable="false"
    >
      <line className={styles.diagramAxis} x1={frame.left} x2={frame.right} y1={y(low)} y2={y(low)} />
      {categories.map((category, index) => (
        <text
          key={index}
          className={styles.diagramLabel}
          x={(x(String(index)) ?? 0) + band / 2}
          y={frame.labelY}
          textAnchor="middle"
        >
          {category}
        </text>
      ))}
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
