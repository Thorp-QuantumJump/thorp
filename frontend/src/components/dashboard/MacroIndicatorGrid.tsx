import { Database } from 'lucide-react'
import { motion } from 'motion/react'
import type { Indicator } from '@/types/dashboard'
import {
  formatDataDate,
  formatIndicatorChange,
  formatIndicatorValue,
} from '@/utils/dashboardFormat'
import { TrendSparkline } from './TrendSparkline'
import styles from './MacroIndicatorGrid.module.css'

interface MacroIndicatorGridProps {
  indicators: Indicator[]
}

/** 시장 국면을 설명하는 월간 거시지표를 역할 중심으로 표시한다. */
export function MacroIndicatorGrid({ indicators }: MacroIndicatorGridProps) {
  return (
    <section className={styles.section} aria-labelledby="macro-indicator-title">
      <div className={styles.sectionHeader}>
        <div>
          <p>왜 이렇게 판단했나요?</p>
          <h2 id="macro-indicator-title">경기·물가·심리 핵심 지표</h2>
        </div>
        <span>
          <Database size={13} aria-hidden="true" />
          발표일과 수정 여부를 함께 표시해요
        </span>
      </div>

      <div className={styles.grid}>
        {indicators.map((indicator, index) => (
          <motion.article
            className={styles.card}
            key={indicator.id}
            initial={{ opacity: 0, y: 28, scale: 0.985 }}
            whileInView={{ opacity: 1, y: 0, scale: 1 }}
            viewport={{ once: false, amount: 0.2 }}
            transition={{
              delay: index * 0.07,
              duration: 0.55,
              ease: [0.22, 1, 0.36, 1],
            }}
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
              <span data-tone={indicator.tone}>{formatIndicatorChange(indicator)}</span>
            </div>
            <div className={styles.chartWrap}>
              <TrendSparkline
                series={indicator.series}
                tone={indicator.tone}
                delay={index * 0.06}
              />
            </div>
            <p className={styles.interpretation}>{indicator.interpretation}</p>
            <div className={styles.meta}>
              <span>{indicator.source.provider}</span>
              <span>{indicator.revisionStatus === 'revised' ? '수정 가능' : '초기 발표'}</span>
            </div>
          </motion.article>
        ))}
      </div>
    </section>
  )
}
