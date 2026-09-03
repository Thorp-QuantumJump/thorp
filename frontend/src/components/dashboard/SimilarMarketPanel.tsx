import { CalendarDays } from 'lucide-react'
import { motion } from 'motion/react'
import type { Indicator, SimilarMarketPeriod } from '@/types/dashboard'
import { formatComparisonValue, formatDataDate } from '@/utils/dashboardFormat'
import styles from './SimilarMarketPanel.module.css'

interface SimilarMarketPanelProps {
  period: SimilarMarketPeriod
  indicators: Indicator[]
}

/** 비교 지표 ID를 사용자가 이해할 수 있는 이름으로 연결한다. */
function getIndicatorName(indicatorId: string, indicators: Indicator[]) {
  return indicators.find((indicator) => indicator.id === indicatorId)?.name ?? indicatorId
}

/** 현재 6개월 흐름과 가장 유사한 과거 구간의 공통점과 차이를 비교한다. */
export function SimilarMarketPanel({ period, indicators }: SimilarMarketPanelProps) {
  return (
    <section className={styles.section} aria-labelledby="similar-market-title">
      <div className={styles.sectionHeader}>
        <div>
          <p>과거와 비교해 보기</p>
          <h2 id="similar-market-title">지금과 비슷했던 시장</h2>
        </div>
        <span>
          <CalendarDays size={13} aria-hidden="true" />
          최근 {period.comparisonWindowMonths}개월 흐름을 비교했어요
        </span>
      </div>

      <motion.div
        className={styles.panel}
        initial={{ opacity: 0, y: 30, scale: 0.99 }}
        whileInView={{ opacity: 1, y: 0, scale: 1 }}
        viewport={{ once: false, amount: 0.18 }}
        transition={{ duration: 0.62, ease: [0.22, 1, 0.36, 1] }}
      >
        <div className={styles.overview}>
          <span className={styles.label}>가장 유사한 구간</span>
          <h3>
            {formatDataDate(period.periodStart)} — {formatDataDate(period.periodEnd)}
          </h3>
          <div className={styles.scoreRow}>
            <div>
              <strong>{period.similarityScore.toFixed(1)}%</strong>
              <span>유사도</span>
            </div>
            <div>
              <strong data-positive>+{period.futureReturn3m.toFixed(1)}%</strong>
              <span>이후 3개월 KOSPI</span>
            </div>
            <div>
              <strong data-positive>+{period.futureReturn6m.toFixed(1)}%</strong>
              <span>이후 6개월 KOSPI</span>
            </div>
          </div>

          <div className={styles.events} aria-label="당시 주요 이벤트">
            {period.events.map((event) => (
              <span key={event}>{event}</span>
            ))}
          </div>

          <div className={styles.observationGrid}>
            <div>
              <h4>공통점</h4>
              <ul>
                {period.similarities.map((similarity) => (
                  <li key={similarity}>{similarity}</li>
                ))}
              </ul>
            </div>
            <div>
              <h4>차이점</h4>
              <ul>
                {period.differences.map((difference) => (
                  <li key={difference}>{difference}</li>
                ))}
              </ul>
            </div>
          </div>
        </div>

        <div className={styles.comparison} role="table" aria-label="현재와 과거 지표 비교">
          <div className={`${styles.comparisonRow} ${styles.comparisonHeader}`} role="row">
            <span role="columnheader">지표</span>
            <span role="columnheader">현재</span>
            <span role="columnheader">과거</span>
          </div>
          {period.comparisons.map((comparison) => (
            <div className={styles.comparisonRow} role="row" key={comparison.indicatorId}>
              <strong role="cell">{getIndicatorName(comparison.indicatorId, indicators)}</strong>
              <span role="cell">
                {formatComparisonValue(comparison.currentValue, comparison.unit)}
              </span>
              <span role="cell">
                {formatComparisonValue(comparison.historicalValue, comparison.unit)}
              </span>
            </div>
          ))}
          <p className={styles.note}>
            유사도와 이후 수익률은 UI 구조 검증용 값이며 실제 분석 결과가 아닙니다.
          </p>
        </div>
      </motion.div>
    </section>
  )
}
