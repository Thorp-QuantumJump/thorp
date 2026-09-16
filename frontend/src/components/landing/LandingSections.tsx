import { ArrowDown, ArrowUpRight, BarChart3, FlaskConical, Radar } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { backtestChartData, type BacktestPoint } from '@/data/backtestMock'
import type { Indicator } from '@/types/market'
import { IndicatorConveyor } from './IndicatorConveyor'
import { MarketAtmosphereCanvas } from './MarketAtmosphereCanvas'
import styles from '@/pages/LandingPage.module.css'

const revealTransition = { duration: 0.78, ease: [0.22, 1, 0.36, 1] as const }

type PreviewSeries = Exclude<keyof BacktestPoint, 'date'>

function buildPreviewPath(series: PreviewSeries) {
  const left = 42
  const top = 40
  const width = 916
  const height = 278
  const minimum = 88
  const maximum = 230

  return backtestChartData
    .map((point, index) => {
      const x = left + (index / (backtestChartData.length - 1)) * width
      const value = Number(point[series])
      const y = top + height - ((value - minimum) / (maximum - minimum)) * height
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
    })
    .join(' ')
}

const comparisonPaths = [
  buildPreviewPath('benchmark'),
  buildPreviewPath('dualMomentum'),
  buildPreviewPath('rsiMeanReversion'),
]
const resultPath = buildPreviewPath('trend')

/** 서비스의 핵심 메시지와 시장 그래픽을 첫 화면에 배치한다. */
export function HeroSection() {
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

      <motion.div
        className={styles.heroContent}
        initial="hidden"
        animate="visible"
        variants={{
          hidden: {},
          visible: { transition: { delayChildren: 0.18, staggerChildren: 0.1 } },
        }}
      >
        <motion.p
          className={styles.eyebrow}
          variants={{ hidden: { opacity: 0 }, visible: { opacity: 1 } }}
        >
          KOREA MARKET INTELLIGENCE
        </motion.p>
        <motion.h1
          id="landing-title"
          variants={{ hidden: { opacity: 0, y: 26 }, visible: { opacity: 1, y: 0 } }}
          transition={revealTransition}
        >
          <span>시장을 확인하고</span>
          <span>전략을 <em>검증하세요.</em></span>
        </motion.h1>
        <motion.p
          className={styles.heroDescription}
          variants={{ hidden: { opacity: 0, y: 14 }, visible: { opacity: 1, y: 0 } }}
          transition={revealTransition}
        >
          데이터로 시장을 읽고, 검증된 전략으로 판단하세요.
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
      <div className={styles.sectionNumber} aria-hidden="true">
        01 / MARKET PULSE
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

        <Link className={styles.textCta} to="/dashboard">
          <span>시장 분석 보러 가기</span>
          <ArrowUpRight aria-hidden="true" size={21} />
        </Link>
      </div>
    </motion.section>
  )
}

/** 기존 예시 시계열로 전략 결과가 과거 시장 위에 그려지는 과정을 보여준다. */
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
          <h2 id="backtest-intro-title">
            나의 전략을
            <br />
            과거에서 실험해보세요.
          </h2>
          <span>같은 시장과 기간에서 전략을 비교하고 수익과 위험을 직접 검증해보세요.</span>
        </div>

        <div className={styles.backtestVisual}>
          <div className={styles.chartTopline}>
            <span><BarChart3 aria-hidden="true" size={17} /> KOSPI 200 / 2019—2023</span>
            <strong>STRATEGY 01</strong>
          </div>
          <svg viewBox="0 0 1000 390" role="img" aria-label="과거 시장과 추세추종 전략의 백테스트 성과 비교 차트">
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
            <g className={styles.chartYears}>
              <text x="42" y="370">2019</text>
              <text x="271" y="370">2020</text>
              <text x="500" y="370">2021</text>
              <text x="729" y="370">2022</text>
              <text x="958" y="370" textAnchor="end">2023</text>
            </g>
          </svg>
          <div className={styles.previewMetrics}>
            <span>누적 수익 <strong>+124.8%</strong></span>
            <span>CAGR <strong>18.4%</strong></span>
            <span>MDD <strong>-9.1%</strong></span>
            <span>SHARPE <strong>1.42</strong></span>
          </div>
        </div>

        <Link className={styles.textCta} to="/backtest">
          <span>백테스트 시작하기</span>
          <ArrowUpRight aria-hidden="true" size={21} />
        </Link>
      </div>
      <div className={styles.sectionNumber} aria-hidden="true">
        02 / TEST THE PAST
      </div>
    </motion.section>
  )
}
