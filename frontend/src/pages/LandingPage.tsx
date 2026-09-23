import { AlgorithmOrbit } from '@/components/landing/AlgorithmOrbit'
import { LandingHeader } from '@/components/landing/LandingHeader'
import {
  BacktestIntroSection,
  HeroSection,
  MarketAnalysisSection,
} from '@/components/landing/LandingSections'
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
      <LandingHeader />
      <HeroSection />
      <div className={styles.storyFlow}>
        <MarketAnalysisSection indicators={indicators} />
        <BacktestIntroSection />
        <AlgorithmOrbit />
      </div>
    </main>
  )
}
