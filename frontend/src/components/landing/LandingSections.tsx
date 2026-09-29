import { ArrowDown, ArrowUpRight } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useId, useRef, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import type { Indicator } from '@/types/market'
import { MarketAtmosphereCanvas } from './MarketAtmosphereCanvas'
import { MarketFlowPreview } from './MarketFlowPreview'
import { MarketPreviewWindow } from './MarketPreviewWindow'
import { useScrollScene } from './scrollScene'
import styles from '@/pages/LandingPage.module.css'

const revealTransition = { duration: 0.78, ease: [0.22, 1, 0.36, 1] as const }

/*
  진단 구역의 결과 화면 예시 차트. 결과 화면의 자산가치 그래프(EquityChart)와 같은 문법으로 그린다 —
  기준 전략은 실선, 비교 대상은 파선, 시작 금액은 가로 점선, 가장 크게 줄었던 구간은 음영이다.

  **성과가 아니다.** 곡선은 사인파로 만든 무늬이고 축 눈금·기간·금액·종목 이름을 달지 않는다. 예전에는
  예시 시계열 옆에 누적 수익·CAGR·MDD·SHARPE를 적었는데, 어떤 실행에서도 나온 적 없는 값이라 첫 화면이
  근거 없는 성과를 단정하는 꼴이었다. 실제 수치는 결과 화면이 자기 실행에서 읽어 보인다.
*/
const resultPlot = { left: 24, right: 852, top: 22, bottom: 300, start: 218 }

type CurvePoint = { x: number; y: number }

/** 시작 금액 자리에서 출발하는 장식 곡선. 같은 씨앗이면 같은 모양이다. */
function buildDecorativeCurve(seed: number, amplitude: number, rise: number): CurvePoint[] {
  const steps = 64
  const offset = Math.sin(seed) + Math.sin(seed) * 0.35
  return Array.from({ length: steps + 1 }, (_, index) => {
    const ratio = index / steps
    const wave = Math.sin(ratio * Math.PI * 2.2 + seed) + Math.sin(ratio * Math.PI * 5.3 + seed) * 0.35 - offset
    return {
      x: resultPlot.left + ratio * (resultPlot.right - resultPlot.left),
      y: resultPlot.start - wave * amplitude * Math.min(1, ratio * 3) - ratio * rise,
    }
  })
}

function toPath(points: CurvePoint[]) {
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(' ')
}

/** 곡선에서 가장 크게 줄었던 구간(고점 → 이후 가장 깊은 저점). 화면 좌표라 값이 클수록 낮다. */
function findDeepestFall(points: CurvePoint[]) {
  let peak = points[0]
  let best = { from: points[0].x, to: points[0].x, depth: 0 }
  for (const point of points) {
    if (point.y < peak.y) peak = point
    const depth = point.y - peak.y
    if (depth > best.depth) best = { from: peak.x, to: point.x, depth }
  }
  return best
}

const baseCurve = buildDecorativeCurve(0.9, 58, 150)
const targetCurve = buildDecorativeCurve(2.6, 34, 70)
const deepestFall = findDeepestFall(baseCurve)
const curveEnds = [
  { key: 'base', name: '기준 전략', y: baseCurve[baseCurve.length - 1].y, stroke: 'var(--chart-line-primary)' },
  { key: 'target', name: '비교 대상', y: targetCurve[targetCurve.length - 1].y, stroke: 'var(--chart-line-secondary)' },
]
const drawTransition = { duration: 1.5, ease: [0.22, 1, 0.36, 1] as const }

/** 왼쪽에 서비스의 핵심 메시지, 오른쪽에 시장 지표 움직임 예시를 두고 뒤에 시장 그래픽을 깐다. */
export function HeroSection({ indicators }: { indicators: Indicator[] }) {
  const reducedMotion = useReducedMotion()
  const section = useRef<HTMLElement>(null)
  const scene = useScrollScene(section, 'leave')

  const moveToMarket = () => {
    document.querySelector('#market-analysis')?.scrollIntoView({
      behavior: reducedMotion ? 'auto' : 'smooth',
      block: 'start',
    })
  }

  return (
    <section ref={section} id="landing-hero" className={styles.hero} aria-labelledby="landing-title">
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
          style={scene.copy}
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
              전략 진단하기
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
          <motion.div style={scene.window}>
            <MarketPreviewWindow indicators={indicators} />
          </motion.div>
        </motion.div>
      </div>

      <div className={styles.heroFootnote} aria-hidden="true">
        <span>01</span>
        <span>SCROLL TO EXPLORE</span>
      </div>
    </section>
  )
}

/** 시장 흐름을 소개한다. 왼쪽 글, 오른쪽에 17개 지표가 흐르는 띠와 일곱 분야 요약을 담은 창. */
export function MarketAnalysisSection() {
  const section = useRef<HTMLElement>(null)
  const scene = useScrollScene(section, 'pass', 'left')
  return (
    <section
      ref={section}
      id="market-analysis"
      className={`${styles.storySection} ${styles.storyLayout}`}
      aria-labelledby="market-analysis-title"
    >
      <motion.div className={styles.storyCopy} style={scene.copy}>
        <h2 id="market-analysis-title">
          <span>지표를 기반으로</span>
          <span><em>시장을 분석</em>합니다.</span>
        </h2>
        <p className={styles.heroDescription}>
          <span>17개 경제지표와 일곱 분야의 흐름을</span>
          <span>한곳에서 확인하세요.</span>
        </p>
        {/* 헤더 메뉴의 '시장 흐름'과 같은 곳으로 보낸다(002 T024). */}
        <div className={styles.heroActions}>
          <Link className={styles.secondaryCta} to="/market-flow">
            시장 분석 보러 가기
            <ArrowUpRight aria-hidden="true" size={18} />
          </Link>
        </div>
      </motion.div>

      <motion.div className={styles.storyWindow} style={scene.window}>
        <MarketFlowPreview />
      </motion.div>
    </section>
  )
}

/** 진단이 무엇을 하는지 세 단계로 말한다. 기본값·권고가 아니라 흐름이다. */
const backtestSteps = [
  { title: '섹터를 고르고 비중을 정해요', note: '처음엔 같은 비중으로 채워 둡니다' },
  { title: '다섯 배분 규칙 중 하나를 골라요', note: '기본값은 출발점일 뿐입니다' },
  { title: '같은 조건의 결과를 나란히 비교해요', note: '비용 차감 후 수익·하락 폭·거래비용' },
]

/** 진단이 무엇을 하는지 알리고 섹터 고르기로 보낸다. 왼쪽에 결과 화면 창, 오른쪽에 글과 세 단계. */
export function BacktestIntroSection() {
  const section = useRef<HTMLElement>(null)
  const scene = useScrollScene(section, 'pass', 'right')
  const revealId = `landing-result-reveal-${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  return (
    <section
      ref={section}
      id="backtest-service"
      className={`${styles.storySection} ${styles.storyLayout} ${styles.storyReverse}`}
      aria-labelledby="backtest-intro-title"
    >
      <motion.div className={styles.storyWindow} style={scene.window}>
        {/* 장식이다. 자료를 읽지 않으므로 축 눈금·기간·수치를 달지 않고 보조기기에서 숨긴다. */}
        <div className={styles.previewWindow} aria-hidden="true">
          <div className={styles.previewBar}>
            <span className={styles.previewDots}><i /><i /><i /></span>
            <span className={styles.previewAddress}>thorp · <b>전략 진단 결과</b></span>
            <span className={styles.previewExample}>예시 곡선</span>
          </div>
          {/* 결과 화면 요약 줄. 무엇을 보게 되는지 이름만 보이고 값은 비운다 — 실행하지 않은 값을
              채우면 첫 화면이 성과를 지어내게 된다. */}
          <dl className={styles.resultMetrics}>
            {['비용 차감 후 수익', '가장 크게 줄었던 폭', '거래비용'].map((name) => (
              <div key={name}><dt>{name}</dt><dd>실행 후 표시</dd></div>
            ))}
          </dl>
          <p className={styles.previewChartTitle}>자산가치 추이 <b>기준 전략 · 비교 대상</b></p>
          <svg className={styles.resultChart} viewBox="0 0 1000 320">
            {[70, 144, 292].map((y) => (
              <line key={y} className={styles.resultGrid} x1={resultPlot.left} x2={resultPlot.right} y1={y} y2={y} />
            ))}
            <motion.g
              initial={{ opacity: 0 }}
              whileInView={{ opacity: 1 }}
              viewport={{ once: false, amount: 0.35 }}
              transition={{ duration: 0.5, delay: 1.1 }}
            >
              <rect
                className={styles.resultShade}
                x={deepestFall.from}
                width={deepestFall.to - deepestFall.from}
                y={resultPlot.top}
                height={resultPlot.bottom - resultPlot.top}
              />
              <line
                className={styles.resultShadeEdge}
                x1={deepestFall.from}
                x2={deepestFall.to}
                y1={resultPlot.top}
                y2={resultPlot.top}
              />
            </motion.g>
            <line className={styles.resultStartLine} x1={resultPlot.left} x2={resultPlot.right} y1={resultPlot.start} y2={resultPlot.start} />
            <text className={styles.resultStartLabel} x={resultPlot.left + 6} y={resultPlot.start + 18}>시작 금액</text>
            {/* 두 곡선을 왼쪽부터 드러낸다. pathLength로 그리면 선 무늬(stroke-dasharray)를 덮어 써서
                비교 대상의 파선이 실선이 되므로, 곡선은 그대로 두고 보이는 폭을 넓힌다. */}
            <defs>
              <clipPath id={revealId}>
                <motion.rect
                  x={0}
                  y={0}
                  height={320}
                  initial={{ width: 0 }}
                  whileInView={{ width: resultPlot.right + 4 }}
                  viewport={{ once: false, amount: 0.35 }}
                  transition={drawTransition}
                />
              </clipPath>
            </defs>
            <g clipPath={`url(#${revealId})`}>
              <path className={styles.resultTargetLine} d={toPath(targetCurve)} />
              <path className={styles.resultBaseLine} d={toPath(baseCurve)} />
            </g>
            {curveEnds.map((end) => (
              <motion.g
                key={end.key}
                initial={{ opacity: 0, x: -6 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: false, amount: 0.35 }}
                transition={{ duration: 0.4, delay: 1.2 }}
              >
                <line stroke={end.stroke} x1={resultPlot.right} x2={resultPlot.right + 10} y1={end.y} y2={end.y} />
                <text className={styles.resultEndLabel} x={resultPlot.right + 16} y={end.y} fill={end.stroke}>{end.name}</text>
              </motion.g>
            ))}
          </svg>
          <div className={styles.resultLegend}>
            <span><i data-kind="start" />시작 금액</span>
            <span><i data-kind="shade" />가장 크게 줄었던 구간</span>
            <span><i data-kind="target" />비교 대상은 파선</span>
          </div>
        </div>
      </motion.div>

      <motion.div className={styles.storyCopy} style={scene.copy}>
        <h2 id="backtest-intro-title">
          <span>나의 전략을</span> <span><em>실험해보세요.</em></span>
        </h2>
        <ol className={styles.storySteps}>
          {backtestSteps.map((step, index) => (
            <li key={step.title}>
              <b aria-hidden="true">{index + 1}</b>
              <span>
                {step.title}
                <small>{step.note}</small>
              </span>
            </li>
          ))}
        </ol>
        {/* 헤더 메뉴의 '전략 진단'과 같은 곳이다(002 T024). 백테스트는 섹터·전략을 고른
            뒤에 돌리는 것이라 그 출발점으로 보낸다. */}
        <div className={styles.heroActions}>
          <Link className={styles.primaryCta} to="/diagnosis/sectors">
            백테스트 시작하기
            <ArrowUpRight aria-hidden="true" size={18} />
          </Link>
        </div>
      </motion.div>
    </section>
  )
}
