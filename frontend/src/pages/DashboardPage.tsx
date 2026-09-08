import { useState } from 'react'
import { DashboardIndicatorStrip } from '@/components/dashboard/DashboardIndicatorStrip'
import { MarketStatusSummary } from '@/components/dashboard/MarketStatusSummary'
import { StrategyRecommendationSection } from '@/components/market/StrategyRecommendationSection'
import { AppHeader } from '@/components/navigation/AppHeader'
import { marketMock } from '@/data/marketMock'
import { useMarketIndicators } from '@/hooks/useMarketIndicators'
import layout from '@/styles/MarketLayout.module.css'
import styles from './DashboardPage.module.css'

/** 시장 요약 → 지표 확인 → 추천 전략 순서로 빠르게 살펴보는 대시보드다. */
export function DashboardPage() {
  const [indicatorGroup, setIndicatorGroup] = useState<'market' | 'macro'>('market')
  const { marketIndicators, macroIndicators } = useMarketIndicators(
    marketMock.marketIndicators,
    marketMock.macroIndicators,
  )

  return (
    <div className={`${layout.page} ${styles.page}`}>
      <AppHeader />
      <main className={styles.container}>
        <MarketStatusSummary regime={marketMock.regime} />

        <DashboardIndicatorStrip
          group={indicatorGroup}
          marketIndicators={marketIndicators}
          macroIndicators={macroIndicators}
          onGroupChange={setIndicatorGroup}
        />

        <StrategyRecommendationSection compact strategies={marketMock.strategyRecommendations} />

        <footer className={layout.footer}>
          지표 최신값은 연동 API를 우선 사용하며, 시장 해석·차트·추천 결과는 UI 검증용 예시
          데이터입니다. 투자 권유나 수익을 보장하는 정보가 아닙니다.
        </footer>
      </main>
    </div>
  )
}
