import { Database } from 'lucide-react'
import type { Indicator } from '@/types/market'
import { selectIndicatorPeriod } from '@/utils/indicatorHistory'
import {
  formatDataDate,
  formatFrequency,
  formatIndicatorContext,
  formatIndicatorValue,
} from '@/utils/marketFormat'
import { TrendSparkline } from './TrendSparkline'
import { IndicatorHistoryCard } from './IndicatorHistoryCard'
import layout from '@/styles/MarketLayout.module.css'
import styles from './MacroIndicatorGrid.module.css'

interface MacroIndicatorGridProps {
  compact?: boolean
  indicators: Indicator[]
  activeIndicatorId: string | null
  onActiveChange: (id: string | null) => void
}

/** 시장 국면을 설명하는 월간 거시지표를 역할 중심으로 표시한다. */
export function MacroIndicatorGrid({ indicators, activeIndicatorId, onActiveChange, compact = false }: MacroIndicatorGridProps) {
  return (
    <section className={`${styles.section} ${compact ? layout.compactSection : ''}`} data-density={compact ? 'compact' : undefined} aria-labelledby="macro-indicator-title">
      <div className={`${styles.sectionHeader} ${compact ? layout.compactHeader : ''}`}>
        <div>
          <h2 id="macro-indicator-title">경기·물가·심리 핵심 지표</h2>
        </div>
        <span>
          <Database size={13} aria-hidden="true" />
          최신 관측값과 출처를 함께 표시해요
        </span>
      </div>

      <div className={styles.grid}>
        {indicators.map((indicator, index) => (
          <IndicatorHistoryCard
            className={styles.card}
            key={indicator.id}
            indicator={indicator}
            index={index}
            variant="macro"
            expanded={activeIndicatorId === indicator.id}
            onActiveChange={onActiveChange}
          >
            <div className={styles.cardHeader}>
              <div>
                <span>{indicator.code}</span>
                <h3>{indicator.name}</h3>
              </div>
              <small>{formatDataDate(indicator.observationDate)} 기준</small>
            </div>
            <div className={styles.valueRow}>
              <strong>{formatIndicatorValue(indicator)}</strong>
              <span
                data-tone={indicator.tone}
                data-status={indicator.dataStatus}
                title={indicator.statusMessage ?? undefined}
              >
                {formatIndicatorContext(indicator)}
              </span>
            </div>
            <div className={styles.chartWrap}>
              <TrendSparkline
                series={selectIndicatorPeriod(indicator.series, indicator.observationDate, 3)}
                tone={indicator.tone}
                delay={index * 0.06}
              />
            </div>
            <p className={styles.interpretation}>{indicator.interpretation}</p>
            <div className={styles.meta}>
              <span>{indicator.source.provider}</span>
              <span>{formatFrequency(indicator.source.frequency)}</span>
            </div>
          </IndicatorHistoryCard>
        ))}
      </div>
    </section>
  )
}
