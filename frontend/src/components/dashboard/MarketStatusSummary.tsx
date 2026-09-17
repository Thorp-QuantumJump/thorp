import { ArrowUpRight, Activity } from 'lucide-react'
import { motion } from 'motion/react'
import { Link } from 'react-router-dom'
import type { MarketRegime } from '@/types/market'
import {
  cycleLabels,
  inflationLabels,
  riskLabels,
  formatDataDate,
} from '@/utils/marketFormat'
import styles from './MarketStatusSummary.module.css'

interface MarketStatusSummaryProps {
  regime: MarketRegime
}

/** 홈과 같은 시장 국면을 경기·물가·위험의 세 가지 상태로 압축해 표시한다. */
export function MarketStatusSummary({ regime }: MarketStatusSummaryProps) {
  return (
    <motion.section
      className={styles.summary}
      aria-labelledby="market-status-title"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45 }}
    >
      <div className={styles.copy}>
        <span className={styles.eyebrow}><Activity size={15} aria-hidden="true" /> 현재 시장 상황</span>
        <h1 id="market-status-title">{regime.headline}</h1>
        <p className={styles.description}>{regime.summary}</p>
      </div>
      <div className={styles.stateArea}>
        <dl className={styles.states}>
          <div><dt>경기</dt><dd>{cycleLabels[regime.cycle]}</dd></div>
          <div><dt>물가</dt><dd>{inflationLabels[regime.inflation]}</dd></div>
          <div><dt>시장 위험</dt><dd data-risk={regime.risk}>{riskLabels[regime.risk]}</dd></div>
        </dl>
        <div className={styles.metaRow}>
          <span>분석 기준 {formatDataDate(regime.asOf)}</span>
          <Link className={styles.detailLink} to="/market-flow">
            자세히 보기 <ArrowUpRight size={14} aria-hidden="true" />
          </Link>
        </div>
      </div>
    </motion.section>
  )
}
