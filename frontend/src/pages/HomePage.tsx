import { useState } from 'react'
import { DataFreshnessPanel } from '@/components/home/DataFreshnessPanel'
import { MacroIndicatorGrid } from '@/components/market/MacroIndicatorGrid'
import { MarketRegimeHero } from '@/components/home/MarketRegimeHero'
import { MarketSnapshot } from '@/components/market/MarketSnapshot'
import { SimilarMarketPanel } from '@/components/home/SimilarMarketPanel'
import { StrategyRecommendationSection } from '@/components/market/StrategyRecommendationSection'
import { AppHeader } from '@/components/navigation/AppHeader'
import { marketMock } from '@/data/marketMock'
import layout from '@/styles/MarketLayout.module.css'
import styles from './HomePage.module.css'

/** 기존 시장 분석 화면을 홈으로 제공하고 상세 지표·차트·유사 시장 탐색을 유지한다. */
export function HomePage() {
  const [activeIndicatorId, setActiveIndicatorId] = useState<string | null>(null)
  const allComparisonIndicators = [
    ...marketMock.marketIndicators,
    ...marketMock.macroIndicators,
  ]

  return (
    <div className={layout.page}>
      <AppHeader />

      <main className={styles.container}>
        <div className={styles.heroStage}>
          <span className={styles.orbitPrimary} aria-hidden="true" />
          <span className={styles.orbitSecondary} aria-hidden="true" />
          <MarketRegimeHero regime={marketMock.regime} />
        </div>

        <div className={styles.contentBoundary}>
          <div className={styles.contentFlow}>
            <MarketSnapshot
              indicators={marketMock.marketIndicators}
              activeIndicatorId={activeIndicatorId}
              onActiveChange={setActiveIndicatorId}
            />
            <MacroIndicatorGrid
              indicators={marketMock.macroIndicators}
              activeIndicatorId={activeIndicatorId}
              onActiveChange={setActiveIndicatorId}
            />
            <SimilarMarketPanel
              period={marketMock.similarPeriod}
              indicators={allComparisonIndicators}
            />
            <StrategyRecommendationSection strategies={marketMock.strategyRecommendations} />
            <DataFreshnessPanel
              sources={marketMock.dataFreshness}
              stabilityIndicators={marketMock.stabilityIndicators}
            />
          </div>

          <footer className={layout.footer}>
            본 화면은 UI 검증을 위한 예시 데이터이며 투자 권유나 수익을 보장하는 정보가
            아닙니다.
          </footer>
        </div>
      </main>
    </div>
  )
}
