import { Clock } from 'lucide-react'
import { motion } from 'motion/react'
import type { Indicator } from '@/types/dashboard'
import {
  formatDataDate,
  formatFrequency,
  formatIndicatorChange,
  formatIndicatorValue,
} from '@/utils/dashboardFormat'
import { TrendSparkline } from './TrendSparkline'
import styles from './MarketSnapshot.module.css'

interface MarketSnapshotProps {
  indicators: Indicator[]
}

/** 거래일 기준 시장지표와 각 데이터의 지연 정보를 카드로 요약한다. */
export function MarketSnapshot({ indicators }: MarketSnapshotProps) {
  return (
    <section className={styles.section} aria-labelledby="market-snapshot-title">
      <div className={styles.sectionHeader}>
        <div>
          <p>오늘의 시장 지표</p>
          <h2 id="market-snapshot-title">주식시장 핵심 지표</h2>
        </div>
        <span>
          <Clock size={13} aria-hidden="true" />
          지표마다 발표일이 달라요
        </span>
      </div>

      <div className={styles.grid}>
        {indicators.map((indicator, index) => (
          <motion.article
            className={styles.card}
            key={indicator.id}
            initial={{ opacity: 0, y: 28, scale: 0.985 }}
            whileInView={{ opacity: 1, y: 0, scale: 1 }}
            viewport={{ once: false, amount: 0.25 }}
            transition={{
              delay: index * 0.07,
              duration: 0.55,
              ease: [0.22, 1, 0.36, 1],
            }}
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
              series={indicator.series}
              tone={indicator.tone}
              delay={index * 0.06}
            />
            <p className={styles.interpretation}>{indicator.interpretation}</p>
            <div className={styles.source}>
              <span>{indicator.source.provider}</span>
              <span>{indicator.source.delayDescription ?? formatFrequency(indicator.source.frequency)}</span>
            </div>
          </motion.article>
        ))}
      </div>
    </section>
  )
}
