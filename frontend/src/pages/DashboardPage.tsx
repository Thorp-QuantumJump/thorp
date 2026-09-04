import { useState } from 'react'
import { DataFreshnessPanel } from '@/components/dashboard/DataFreshnessPanel'
import { MacroIndicatorGrid } from '@/components/dashboard/MacroIndicatorGrid'
import { MarketRegimeHero } from '@/components/dashboard/MarketRegimeHero'
import { MarketSnapshot } from '@/components/dashboard/MarketSnapshot'
import { SimilarMarketPanel } from '@/components/dashboard/SimilarMarketPanel'
import { StrategyRecommendationSection } from '@/components/dashboard/StrategyRecommendationSection'
import { AppHeader } from '@/components/navigation/AppHeader'
import { dashboardMock } from '@/data/dashboardMock'
import styles from './DashboardPage.module.css'

/** 한국 시장 국면, 유사 과거 구간과 전략 추천 컴포넌트를 하나의 화면으로 조합한다. */
export function DashboardPage() {
  const [activeIndicatorId, setActiveIndicatorId] = useState<string | null>(null)
  const allComparisonIndicators = [
    ...dashboardMock.marketIndicators,
    ...dashboardMock.macroIndicators,
  ]

  return (
    <div className={styles.page}>
      <AppHeader />

      <main className={styles.container}>
        <div className={styles.heroStage}>
          <span className={styles.orbitPrimary} aria-hidden="true" />
          <span className={styles.orbitSecondary} aria-hidden="true" />
          <MarketRegimeHero regime={dashboardMock.regime} />
        </div>

        <div className={styles.contentBoundary}>
          <div className={styles.contentFlow}>
            <MarketSnapshot
              indicators={dashboardMock.marketIndicators}
              activeIndicatorId={activeIndicatorId}
              onActiveChange={setActiveIndicatorId}
            />
            <MacroIndicatorGrid
              indicators={dashboardMock.macroIndicators}
              activeIndicatorId={activeIndicatorId}
              onActiveChange={setActiveIndicatorId}
            />
            <SimilarMarketPanel
              period={dashboardMock.similarPeriod}
              indicators={allComparisonIndicators}
            />
            <StrategyRecommendationSection strategies={dashboardMock.strategyRecommendations} />
            <DataFreshnessPanel
              sources={dashboardMock.dataFreshness}
              stabilityIndicators={dashboardMock.stabilityIndicators}
            />
          </div>

          <footer className={styles.footer}>
            본 화면은 UI 검증을 위한 예시 데이터이며 투자 권유나 수익을 보장하는 정보가
            아닙니다.
          </footer>
        </div>
      </main>
    </div>
  )
}
