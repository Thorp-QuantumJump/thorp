import { useScroll } from 'motion/react'
import { useRef } from 'react'
import { AlgorithmOrbit } from '@/components/landing/AlgorithmOrbit'
import {
  BacktestIntroSection,
  HeroSection,
  MarketAnalysisSection,
} from '@/components/landing/LandingSections'
import { LandingScrollContext } from '@/components/landing/scrollScene'
import { SectionProgress } from '@/components/landing/SectionProgress'
import { AppHeader } from '@/components/navigation/AppHeader'
import { marketMock } from '@/data/marketMock'
import { useMarketIndicators } from '@/hooks/useMarketIndicators'
import styles from './LandingPage.module.css'

/** 칸 이동 점이 가리키는 칸들. 순서가 화면 순서와 같다. */
const SECTION_IDS = ['landing-hero', 'market-analysis', 'backtest-service', 'algorithms']

/** 시장 분석부터 전략 검증까지 Thorp의 핵심 흐름을 순차적으로 소개한다. */
export function LandingPage() {
  const { marketIndicators, macroIndicators } = useMarketIndicators(
    marketMock.marketIndicators,
    marketMock.macroIndicators,
  )
  const indicators = [...marketIndicators, ...macroIndicators]
  // 첫 화면은 이 main이 스스로 스크롤한다(한 칸씩 넘기기). 칸들의 움직임과 칸 이동 점이 이 스크롤을 읽는다.
  const page = useRef<HTMLElement>(null)
  const { scrollYProgress } = useScroll({ container: page })

  return (
    <LandingScrollContext.Provider value={page}>
      <main className={styles.page} ref={page}>
        {/* 다른 화면과 같은 상단 바다. 로그인 상태를 읽으므로 로그인한 사용자에게 로그인 버튼을 보이지 않는다. */}
        <AppHeader />
        <HeroSection indicators={indicators} />
        <SectionProgress progress={scrollYProgress} targets={SECTION_IDS} />
        <div className={styles.storyFlow}>
          <MarketAnalysisSection indicators={indicators} />
          <BacktestIntroSection />
          <AlgorithmOrbit />
        </div>
      </main>
    </LandingScrollContext.Provider>
  )
}
