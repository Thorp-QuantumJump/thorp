import { Clock } from 'lucide-react'
import type { Indicator } from '@/types/market'
import { selectIndicatorPeriod } from '@/utils/indicatorHistory'
import {
  formatDataDate,
  formatFrequency,
  formatIndicatorChange,
  formatIndicatorValue,
} from '@/utils/marketFormat'
import { TrendSparkline } from './TrendSparkline'
import { IndicatorHistoryCard } from './IndicatorHistoryCard'
import layout from '@/styles/MarketLayout.module.css'
import styles from './MarketSnapshot.module.css'

interface MarketSnapshotProps {
  compact?: boolean
  indicators: Indicator[]
  activeIndicatorId: string | null
  onActiveChange: (id: string | null) => void
}

/** 거래일 기준 시장지표와 각 데이터의 지연 정보를 카드로 요약한다. */
export function MarketSnapshot({ indicators, activeIndicatorId, onActiveChange, compact = false }: MarketSnapshotProps) {
  return (
    <section className={`${styles.section} ${compact ? layout.compactSection : ''}`} data-density={compact ? 'compact' : undefined} aria-labelledby="market-snapshot-title">
      <div className={`${styles.sectionHeader} ${compact ? layout.compactHeader : ''}`}>
        <div>
          <h2 id="market-snapshot-title">주식시장 핵심 지표</h2>
        </div>
        <span>
          <Clock size={13} aria-hidden="true" />
          지표마다 발표일이 달라요
        </span>
      </div>

      <div className={styles.grid}>
        {indicators.map((indicator, index) => (
          <IndicatorHistoryCard
            className={styles.card}
            key={indicator.id}
            indicator={indicator}
            index={index}
            variant="market"
            expanded={activeIndicatorId === indicator.id}
            onActiveChange={onActiveChange}
          >
            <div className={styles.cardTop}>
              <span>{indicator.name}</span>
              <small>{formatDataDate(indicator.observationDate)}</small>
            </div>
            <strong>{formatIndicatorValue(indicator)}</strong>
            <p className={styles.change} data-tone={indicator.tone}>
              {formatIndicatorChange(indicator)}
            </p>
            <TrendSparkline
              series={selectIndicatorPeriod(indicator.series, indicator.observationDate, 3)}
              tone={indicator.tone}
              delay={index * 0.06}
            />
            <p className={styles.interpretation}>{indicator.interpretation}</p>
            <div className={styles.source}>
              <span>{indicator.source.provider}</span>
              <span>{indicator.source.delayDescription ?? formatFrequency(indicator.source.frequency)}</span>
            </div>
          </IndicatorHistoryCard>
        ))}
      </div>
    </section>
  )
}
