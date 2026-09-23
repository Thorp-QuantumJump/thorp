import { ArrowDown, ArrowUpRight, FlaskConical, Radar } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import type { Indicator } from '@/types/market'
import { IndicatorConveyor } from './IndicatorConveyor'
import { MarketAtmosphereCanvas } from './MarketAtmosphereCanvas'
import { NextSectionButton } from './NextSectionButton'
import { MarketPreviewWindow } from './MarketPreviewWindow'
import styles from '@/pages/LandingPage.module.css'

const revealTransition = { duration: 0.78, ease: [0.22, 1, 0.36, 1] as const }

/**
 * 진단 구역의 장식용 곡선을 만든다.
 *
 * <p><b>성과가 아니다.</b> 예전에는 예시 시계열을 실제 백테스트 결과처럼 그리고 그 옆에 누적
 * 수익·CAGR·MDD·SHARPE를 적었는데, 어떤 실행에서도 나온 적 없는 값이라 첫 화면이 근거 없는
 * 성과를 단정하는 꼴이었다. 지금 이 곡선은 사인파로 만든 무늬일 뿐이고 축·기간·종목 이름을
 * 달지 않는다. 실제 수치는 결과 화면이 자기 실행에서 읽어 보인다.
 */
function buildDecorativeCurve(seed: number, amplitude: number) {
  const left = 42
  const width = 916
  const middle = 190
  const steps = 48

  return Array.from({ length: steps + 1 }, (_, index) => {
    const ratio = index / steps
    const x = left + ratio * width
    const wave = Math.sin(ratio * Math.PI * 1.6 + seed) + Math.sin(ratio * Math.PI * 3.1 + seed) * 0.35
    const y = middle - wave * amplitude - ratio * 34
    return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
  }).join(' ')
}

const comparisonPaths = [
  buildDecorativeCurve(0.4, 74),
  buildDecorativeCurve(1.7, 58),
  buildDecorativeCurve(2.9, 44),
]
const resultPath = buildDecorativeCurve(0.9, 96)

/** 왼쪽에 서비스의 핵심 메시지, 오른쪽에 시장 지표 움직임 예시를 두고 뒤에 시장 그래픽을 깐다. */
export function HeroSection({ indicators }: { indicators: Indicator[] }) {
  const reducedMotion = useReducedMotion()

  const moveToMarket = () => {
    document.querySelector('#market-analysis')?.scrollIntoView({
      behavior: reducedMotion ? 'auto' : 'smooth',
      block: 'start',
    })
  }

  return (
    <section className={styles.hero} aria-labelledby="landing-title">
      <div className={styles.heroFallback} aria-hidden="true">
        <span className={styles.heroGlow} />
        <svg className={styles.heroChart} viewBox="0 0 1600 700" preserveAspectRatio="none">
          <defs>
            <linearGradient id="landing-chart-gradient" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" stopColor="#03c75a" stopOpacity="0" />
              <stop offset="0.52" stopColor="#03c75a" stopOpacity="0.65" />
              <stop offset="1" stopColor="#86f2a8" stopOpacity="0.12" />
            </linearGradient>
          </defs>
          <path
            d="M0 505 L80 492 L160 520 L240 458 L320 476 L400 420 L480 451 L560 388 L640 405 L720 330 L800 360 L880 298 L960 346 L1040 260 L1120 292 L1200 224 L1280 242 L1360 180 L1440 210 L1520 138 L1600 162"
          />
          <path
            className={styles.heroChartGlow}
            d="M0 505 L80 492 L160 520 L240 458 L320 476 L400 420 L480 451 L560 388 L640 405 L720 330 L800 360 L880 298 L960 346 L1040 260 L1120 292 L1200 224 L1280 242 L1360 180 L1440 210 L1520 138 L1600 162"
          />
        </svg>
        <div className={styles.candleField}>
          {Array.from({ length: 26 }, (_, index) => (
            <span
              key={index}
              style={{
                '--candle-height': `${22 + ((index * 17) % 54)}px`,
                '--candle-shift': `${(index % 5) * 8}px`,
                '--candle-delay': `${index * -0.08}s`,
              } as CSSProperties}
            />
          ))}
        </div>
      </div>
      <MarketAtmosphereCanvas />

      <div className={styles.heroLayout}>
        <motion.div
          className={styles.heroContent}
          initial="hidden"
          animate="visible"
          variants={{
            hidden: {},
            visible: { transition: { delayChildren: 0.18, staggerChildren: 0.1 } },
          }}
        >
          <motion.h1
            id="landing-title"
            variants={{ hidden: { opacity: 0, y: 26 }, visible: { opacity: 1, y: 0 } }}
            transition={revealTransition}
          >
            <span>내 전략이 얼마나 유효한지,</span>
            <span><em>과거로 돌아가</em></span>
            <span>확인해 볼까요?</span>
          </motion.h1>
          <motion.p
            className={styles.heroDescription}
            variants={{ hidden: { opacity: 0, y: 14 }, visible: { opacity: 1, y: 0 } }}
            transition={revealTransition}
          >
            {/* 뜻이 나뉘는 자리에서 줄을 바꾼다. 폭에 맡기면 '비교합니다.'만 아래로 떨어진다. */}
            <span>국내 섹터 ETF로 다섯 가지 배분 규칙을</span>
            <span>같은 조건에서 비교합니다.</span>
          </motion.p>
          <motion.div
            className={styles.heroActions}
            variants={{ hidden: { opacity: 0, y: 14 }, visible: { opacity: 1, y: 0 } }}
            transition={revealTransition}
          >
            {/* 첫 화면의 가장 중요한 행동이다. 옆의 보조 버튼은 이 페이지 안을 내려갈 뿐이라
                다른 화면으로 보내지 않는다. */}
            <Link className={styles.primaryCta} to="/diagnosis/sectors">
              비중 비교하기
              <ArrowUpRight aria-hidden="true" size={18} />
            </Link>
            <button type="button" className={styles.secondaryCta} onClick={moveToMarket}>
              서비스 알아보기
              <ArrowDown aria-hidden="true" size={17} />
            </button>
          </motion.div>
        </motion.div>

        <motion.div
          className={styles.heroPreview}
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ ...revealTransition, delay: 0.3 }}
        >
          <MarketPreviewWindow indicators={indicators} />
        </motion.div>
      </div>

      <div className={styles.heroFootnote} aria-hidden="true">
        <span>01</span>
        <span>SCROLL TO EXPLORE</span>
      </div>
    </section>
  )
}

/** 실제 연동 지표를 비대칭 레이아웃과 무한 스트립으로 소개한다. */
export function MarketAnalysisSection({ indicators }: { indicators: Indicator[] }) {
  return (
    <motion.section
      id="market-analysis"
      className={`${styles.storySection} ${styles.marketSection}`}
      initial={{ opacity: 0, x: 72 }}
      whileInView={{ opacity: 1, x: 0 }}
      viewport={{ once: true, amount: 0.18 }}
      transition={revealTransition}
      aria-labelledby="market-analysis-title"
    >
      <div className={styles.sectionAside}>
        <span className={styles.sectionNumber} aria-hidden="true">
          01 / MARKET PULSE
        </span>
        <NextSectionButton targetId="backtest-service" label="백테스트 소개로 이동" />
      </div>
      <div className={styles.marketContent}>
        <div className={styles.sectionHeading}>
          <Radar aria-hidden="true" size={22} strokeWidth={1.6} />
          <p>MARKET ANALYSIS</p>
          <h2 id="market-analysis-title">
            지표를 기반으로
            <br />
            시장을 분석합니다.
          </h2>
          <span>주요 시장·거시 지표를 한곳에서 확인하고 현재 시장의 흐름을 읽어보세요.</span>
        </div>

        <IndicatorConveyor indicators={indicators} />

        {/* 헤더 메뉴의 '시장 흐름'과 같은 곳으로 보낸다(002 T024). 같은 성격의 목적지가 두
            곳이면 첫 화면과 메뉴가 서로 다른 화면을 가리킨다. */}
        <Link className={styles.textCta} to="/market-flow">
          <span>시장 분석 보러 가기</span>
          <ArrowUpRight aria-hidden="true" size={21} />
        </Link>
      </div>
    </motion.section>
  )
}

/** 진단이 무엇을 하는지 알리고 섹터 고르기로 보낸다. 성과 수치는 결과 화면에만 둔다. */
export function BacktestIntroSection() {
  return (
    <motion.section
      id="backtest-service"
      className={`${styles.storySection} ${styles.backtestSection}`}
      initial={{ opacity: 0, x: -72 }}
      whileInView={{ opacity: 1, x: 0 }}
      viewport={{ once: true, amount: 0.18 }}
      transition={revealTransition}
      aria-labelledby="backtest-intro-title"
    >
      <div className={styles.backtestContent}>
        <div className={styles.sectionHeading}>
          <FlaskConical aria-hidden="true" size={22} strokeWidth={1.6} />
          <p>BACKTEST STUDIO</p>
          <h2 id="backtest-intro-title">나의 전략을 실험해보세요.</h2>
        </div>

        {/* 장식이다. 자료를 읽지 않으므로 축·기간·수치를 달지 않고 보조기기에서 숨긴다. */}
        <div className={styles.backtestVisual} aria-hidden="true">
          {/* 결과 화면 틀. 무엇을 보게 되는지 이름만 보이고 값은 비운다 — 실행하지 않은 값을
              채우면 첫 화면이 성과를 지어내게 된다. classic 디자인에서는 CSS가 숨긴다. */}
          <div className={styles.visualBar}>
            <span className={styles.visualDots}><i /><i /><i /></span>
            <b>결과 화면</b>
          </div>
          <dl className={styles.visualMetrics}>
            {['비용 차감 후 수익', '가장 크게 줄었던 폭', '거래비용'].map((name) => (
              <div key={name}><dt>{name}</dt><dd>실행 후 표시</dd></div>
            ))}
          </dl>
          <svg viewBox="0 0 1000 390">
            {[86, 144, 202, 260, 318].map((y) => (
              <line key={y} className={styles.chartGrid} x1="42" x2="958" y1={y} y2={y} />
            ))}
            {comparisonPaths.map((path, index) => (
              <motion.path
                key={path}
                className={styles.comparisonPath}
                d={path}
                initial={{ pathLength: 0, opacity: 0 }}
                whileInView={{ pathLength: 1, opacity: 0.34 + index * 0.08 }}
                viewport={{ once: false, amount: 0.35 }}
                transition={{ duration: 1.1, delay: index * 0.08, ease: 'easeOut' }}
              />
            ))}
            <motion.path
              className={styles.resultPathGlow}
              d={resultPath}
              initial={{ pathLength: 0, opacity: 0 }}
              whileInView={{ pathLength: 1, opacity: 1 }}
              viewport={{ once: false, amount: 0.35 }}
              transition={{ duration: 1.55, delay: 0.35, ease: [0.22, 1, 0.36, 1] }}
            />
            <motion.path
              className={styles.resultPath}
              d={resultPath}
              initial={{ pathLength: 0 }}
              whileInView={{ pathLength: 1 }}
              viewport={{ once: false, amount: 0.35 }}
              transition={{ duration: 1.55, delay: 0.35, ease: [0.22, 1, 0.36, 1] }}
            />
          </svg>
        </div>

        {/* 헤더 메뉴의 '전략 진단'과 같은 곳이다(002 T024). 백테스트는 섹터·전략을 고른
            뒤에 돌리는 것이라 그 출발점으로 보낸다. */}
        <Link className={styles.textCta} to="/diagnosis/sectors">
          <span>백테스트 시작하기</span>
          <ArrowUpRight aria-hidden="true" size={21} />
        </Link>
      </div>
      <div className={styles.sectionAside}>
        <span className={styles.sectionNumber} aria-hidden="true">
          02 / TEST THE PAST
        </span>
        <NextSectionButton targetId="algorithms" label="알고리즘 소개로 이동" />
      </div>
    </motion.section>
  )
}
