import { AnimatePresence, motion } from 'motion/react'
import type { Indicator } from '@/types/market'
import { selectIndicatorPeriod } from '@/utils/indicatorHistory'
import {
  formatDataDate,
  formatIndicatorContext,
  formatIndicatorValue,
} from '@/utils/marketFormat'
import { TrendSparkline } from '@/components/market/TrendSparkline'
import styles from './DashboardIndicatorStrip.module.css'

interface DashboardIndicatorStripProps {
  group: 'market' | 'macro'
  marketIndicators: Indicator[]
  macroIndicators: Indicator[]
  onGroupChange: (group: 'market' | 'macro') => void
}

/** 선택한 지표군을 한 줄짜리 요약 카드로 전환해 보여준다. */
export function DashboardIndicatorStrip({
  group,
  marketIndicators,
  macroIndicators,
  onGroupChange,
}: DashboardIndicatorStripProps) {
  const indicators = group === 'market' ? marketIndicators : macroIndicators

  return (
    <section className={styles.section} aria-labelledby="dashboard-indicator-title">
      <div className={styles.header}>
        <h2 id="dashboard-indicator-title">지표 요약</h2>
        <div className={styles.toggle} role="group" aria-label="지표 분류">
          <button
            type="button"
            aria-pressed={group === 'market'}
            onClick={() => onGroupChange('market')}
          >
            시장
          </button>
          <button
            type="button"
            aria-pressed={group === 'macro'}
            onClick={() => onGroupChange('macro')}
          >
            거시
          </button>
        </div>
      </div>

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          className={styles.grid}
          data-group={group}
          key={group}
          initial={{ opacity: 0, x: 14 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: -14 }}
          transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
        >
          {indicators.map((indicator, index) => (
            <article className={styles.card} key={indicator.id}>
              <div className={styles.cardCopy}>
                <div className={styles.cardHeader}>
                  <h3>{indicator.name}</h3>
                  <small>{formatDataDate(indicator.observationDate)}</small>
                </div>
                <strong>{formatIndicatorValue(indicator)}</strong>
                <span
                  className={styles.change}
                  data-tone={indicator.tone}
                  data-status={indicator.dataStatus}
                  title={indicator.statusMessage ?? undefined}
                >
                  {formatIndicatorContext(indicator)}
                </span>
              </div>
              <TrendSparkline
                series={selectIndicatorPeriod(indicator.series, indicator.observationDate, 3)}
                tone={indicator.tone}
                delay={index * 0.04}
                addDetail
              />
            </article>
          ))}
        </motion.div>
      </AnimatePresence>
    </section>
  )
}
