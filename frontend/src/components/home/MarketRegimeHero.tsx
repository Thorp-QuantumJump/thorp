import { ChartNoAxesCombined } from 'lucide-react'
import { motion } from 'motion/react'
import type { MarketRegime } from '@/types/market'
import { cycleLabels, inflationLabels, riskLabels } from '@/utils/marketFormat'
import { MarketOverviewCarousel } from './MarketOverviewCarousel'
import styles from './MarketRegimeHero.module.css'

interface MarketRegimeHeroProps {
  regime: MarketRegime
}

/** 경기·물가·시장 위험을 조합한 한국 시장의 대표 진단을 표시한다. */
export function MarketRegimeHero({ regime }: MarketRegimeHeroProps) {
  return (
    <motion.section
      className={styles.hero}
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45, ease: [0.22, 1, 0.36, 1] }}
      aria-labelledby="market-regime-title"
    >
      <div className={styles.copyColumn}>
        <span className={styles.kicker}>
          <ChartNoAxesCombined size={15} aria-hidden="true" />
          KOREA MARKET INTELLIGENCE
        </span>

        <h1 id="market-regime-title">{regime.headline}</h1>
        <p className={styles.summary}>{regime.summary}</p>

        <div className={styles.regimeLine} aria-label="한국 시장 국면">
          <span>
            경기 <strong>{cycleLabels[regime.cycle]}</strong>
          </span>
          <span>
            물가 <strong>{inflationLabels[regime.inflation]}</strong>
          </span>
          <span>
            시장 <strong data-risk="true">{riskLabels[regime.risk]}</strong>
          </span>
        </div>

        <div className={styles.driverGrid} aria-label="핵심 판단 근거">
          {regime.drivers.map((driver, index) => (
            <div className={styles.driver} key={driver}>
              <span>{String(index + 1).padStart(2, '0')}</span>
              <p>{driver}</p>
            </div>
          ))}
        </div>
      </div>

      <MarketOverviewCarousel
        charts={regime.overviewCharts}
        asOf={regime.asOf}
        isMock={regime.isMock}
      />
    </motion.section>
  )
}
