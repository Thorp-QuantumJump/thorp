import type { CSSProperties } from 'react'
import type { Indicator } from '@/types/market'
import styles from '@/pages/LandingPage.module.css'

interface IndicatorConveyorProps {
  indicators: Indicator[]
}

function formatValue(indicator: Indicator) {
  const value = new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: indicator.decimals,
    maximumFractionDigits: indicator.decimals,
  }).format(indicator.value)
  return `${value}${indicator.unit === '원' ? '원' : ` ${indicator.unit}`}`
}

function createSparkline(indicator: Indicator) {
  const values = indicator.series.map((point) => point.value)
  if (values.length < 2) return ''

  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  const range = maximum - minimum || 1

  return values
    .map((value, index) => {
      const x = (index / (values.length - 1)) * 94 + 3
      const y = 29 - ((value - minimum) / range) * 22
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

function getStatusLabel(indicator: Indicator) {
  switch (indicator.dataStatus) {
    case 'live':
      return `LIVE · ${indicator.source.provider}`
    case 'stale':
      return '갱신 지연'
    case 'loading':
      return '불러오는 중'
    case 'error':
      return '연결 실패 · 예시값'
    default:
      return '예시 데이터'
  }
}

function IndicatorItem({ indicator }: { indicator: Indicator }) {
  const changePrefix = indicator.change !== null && indicator.change > 0 ? '+' : ''
  const toneStyle = { '--indicator-tone': `var(--landing-${indicator.tone})` } as CSSProperties

  return (
    <article
      className={styles.indicatorItem}
      data-status={indicator.dataStatus ?? 'example'}
      style={toneStyle}
      title={indicator.statusMessage ?? undefined}
    >
      <div className={styles.indicatorMeta}>
        <span>{indicator.code.replaceAll('_', ' ')}</span>
        <small>{getStatusLabel(indicator)}</small>
      </div>
      <div className={styles.indicatorReading}>
        <strong>{formatValue(indicator)}</strong>
        <span>
          {indicator.change === null ? '발표 대기' : `${changePrefix}${indicator.change} ${indicator.changeUnit ?? ''}`}
        </span>
      </div>
      <svg viewBox="0 0 100 32" role="img" aria-label={`${indicator.name} 최근 흐름`}>
        <line x1="3" y1="29" x2="97" y2="29" />
        <polyline points={createSparkline(indicator)} />
      </svg>
    </article>
  )
}

/** 시장과 거시 지표를 끊김 없는 가로 스트립으로 표시한다. */
export function IndicatorConveyor({ indicators }: IndicatorConveyorProps) {
  return (
    <div className={styles.conveyorViewport} tabIndex={0} aria-label="주요 시장·거시 지표 흐름">
      <div className={styles.conveyorTrack}>
        <div className={styles.conveyorGroup}>
          {indicators.map((indicator) => <IndicatorItem key={indicator.id} indicator={indicator} />)}
        </div>
        <div className={styles.conveyorGroup} aria-hidden="true">
          {indicators.map((indicator) => <IndicatorItem key={`copy-${indicator.id}`} indicator={indicator} />)}
        </div>
      </div>
    </div>
  )
}
