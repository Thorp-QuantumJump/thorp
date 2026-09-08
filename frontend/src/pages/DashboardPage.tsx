import { useState } from 'react'
import { DashboardIndicatorStrip } from '@/components/dashboard/DashboardIndicatorStrip'
import { MarketStatusSummary } from '@/components/dashboard/MarketStatusSummary'
import { StrategyRecommendationSection } from '@/components/market/StrategyRecommendationSection'
import { AppHeader } from '@/components/navigation/AppHeader'
import { marketMock } from '@/data/marketMock'
import layout from '@/styles/MarketLayout.module.css'
import styles from './DashboardPage.module.css'

/** 시장 요약 → 지표 확인 → 추천 전략 순서로 빠르게 살펴보는 대시보드다. */
export function DashboardPage() {
  const [indicatorGroup, setIndicatorGroup] = useState<'market' | 'macro'>('market')

  return (
    <div className={`${layout.page} ${styles.page}`}>
      <AppHeader />
      <main className={styles.container}>
        <MarketStatusSummary regime={marketMock.regime} />

        <DashboardIndicatorStrip
          group={indicatorGroup}
          marketIndicators={marketMock.marketIndicators}
          macroIndicators={marketMock.macroIndicators}
          onGroupChange={setIndicatorGroup}
        />

        <StrategyRecommendationSection compact strategies={marketMock.strategyRecommendations} />

        <footer className={layout.footer}>
          본 화면은 UI 검증을 위한 예시 데이터이며 투자 권유나 수익을 보장하는 정보가 아닙니다.
        </footer>
      </main>
    </div>
  )
}
