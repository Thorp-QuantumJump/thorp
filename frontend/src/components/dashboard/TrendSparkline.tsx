import { motion } from 'motion/react'
import type { IndicatorSeriesPoint, IndicatorTone } from '@/types/dashboard'
import styles from './TrendSparkline.module.css'

interface TrendSparklineProps {
  series: IndicatorSeriesPoint[]
  tone: IndicatorTone
  delay?: number
}

const width = 160
const height = 48
const padding = 3

/** 작은 시계열을 카드 폭에 맞는 SVG 경로로 정규화한다. */
function buildSparklinePath(series: IndicatorSeriesPoint[]) {
  if (series.length === 0) return ''

  const values = series.map((point) => point.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const xStep = (width - padding * 2) / Math.max(series.length - 1, 1)

  return series
    .map((point, index) => {
      const x = padding + index * xStep
      const y = height - padding - ((point.value - min) / range) * (height - padding * 2)
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
    })
    .join(' ')
}

/** 색상에 의존하지 않는 텍스트 정보 옆에 보조 추세선을 표시한다. */
export function TrendSparkline({ series, tone, delay = 0 }: TrendSparklineProps) {
  const path = buildSparklinePath(series)

  return (
    <svg
      className={styles.sparkline}
      data-tone={tone}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <path className={styles.baseline} d={`M 0 ${height - 1} L ${width} ${height - 1}`} />
      <motion.path
        className={styles.line}
        d={path}
        fill="none"
        initial={{ pathLength: 0, opacity: 0 }}
        whileInView={{ pathLength: 1, opacity: 1 }}
        viewport={{ once: false, amount: 0.65 }}
        transition={{
          delay: 0.16 + delay,
          duration: 1.05,
          ease: [0.22, 1, 0.36, 1],
        }}
      />
    </svg>
  )
}
