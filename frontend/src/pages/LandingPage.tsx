import { AlgorithmOrbit } from '@/components/landing/AlgorithmOrbit'
import {
  BacktestIntroSection,
  HeroSection,
  MarketAnalysisSection,
} from '@/components/landing/LandingSections'
import { AppHeader } from '@/components/navigation/AppHeader'
import { marketMock } from '@/data/marketMock'
import { useMarketIndicators } from '@/hooks/useMarketIndicators'
import styles from './LandingPage.module.css'

/** 시장 분석부터 전략 검증까지 Thorp의 핵심 흐름을 순차적으로 소개한다. */
export function LandingPage() {
  const { marketIndicators, macroIndicators } = useMarketIndicators(
    marketMock.marketIndicators,
    marketMock.macroIndicators,
  )
  const indicators = [...marketIndicators, ...macroIndicators]

  return (
    <main className={styles.page}>
      {/* 다른 화면과 같은 상단 바다. 로그인 상태를 읽으므로 로그인한 사용자에게 로그인 버튼을 보이지 않는다. */}
      <AppHeader />
      <HeroSection />
      <div className={styles.storyFlow}>
        <MarketAnalysisSection indicators={indicators} />
        <BacktestIntroSection />
        <AlgorithmOrbit />
      </div>
    </main>
  )
}
