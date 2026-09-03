import { Pause, Play, ShieldAlert } from 'lucide-react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useState } from 'react'
import type { MarketOverviewChart } from '@/types/dashboard'
import { formatDataDate } from '@/utils/dashboardFormat'
import { MarketRegimeChart } from './MarketRegimeChart'
import styles from './MarketOverviewCarousel.module.css'

interface MarketOverviewCarouselProps {
  charts: MarketOverviewChart[]
  asOf: string
  isMock: boolean
}

const rotationIntervalMs = 7000

/** 현재 지표의 표기 자릿수와 단위를 유지해 최신 값을 표시한다. */
function formatCurrentValue(chart: MarketOverviewChart) {
  const latestValue = chart.series.at(-1)?.value
  if (latestValue === undefined) return '-'

  const formatted = new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: chart.valueDecimals,
    maximumFractionDigits: chart.valueDecimals,
  }).format(latestValue)

  return chart.valueUnit ? `${formatted} ${chart.valueUnit}` : formatted
}

/** 세 가지 시장 차트를 일정 시간마다 넘기고 사용자가 직접 선택하거나 멈출 수 있게 한다. */
export function MarketOverviewCarousel({
  charts,
  asOf,
  isMock,
}: MarketOverviewCarouselProps) {
  const prefersReducedMotion = useReducedMotion()
  const [activeIndex, setActiveIndex] = useState(0)
  const [isPaused, setIsPaused] = useState(false)
  const activeChart = charts[activeIndex] ?? charts[0]

  useEffect(() => {
    if (isPaused || charts.length < 2) return

    const timer = window.setTimeout(() => {
      setActiveIndex((currentIndex) => (currentIndex + 1) % charts.length)
    }, rotationIntervalMs)

    return () => window.clearTimeout(timer)
  }, [activeIndex, charts.length, isPaused])

  if (!activeChart) return null

  return (
    <aside
      className={styles.chartPanel}
      aria-label="한국 시장 주요 지표 장기 추이"
      aria-roledescription="carousel"
    >
      <div className={styles.windowBar}>
        <div className={styles.windowIdentity}>
          <span aria-hidden="true" />
          MARKET PULSE
        </div>

        <div className={styles.controls}>
          <div className={styles.chartTabs} role="tablist" aria-label="표시할 시장 지표">
            {charts.map((chart, index) => (
              <button
                key={chart.id}
                type="button"
                role="tab"
                id={`market-chart-tab-${chart.id}`}
                aria-controls="market-chart-panel"
                aria-selected={activeIndex === index}
                onClick={() => setActiveIndex(index)}
              >
                {chart.tabLabel}
              </button>
            ))}
          </div>

          <button
            className={styles.pauseButton}
            type="button"
            aria-label={isPaused ? '차트 자동 전환 재생' : '차트 자동 전환 일시정지'}
            aria-pressed={isPaused}
            onClick={() => setIsPaused((currentValue) => !currentValue)}
          >
            {isPaused ? <Play size={13} aria-hidden="true" /> : <Pause size={13} aria-hidden="true" />}
          </button>
        </div>
      </div>

      <div className={styles.progressTrack} aria-hidden="true">
        {!isPaused && (
          <motion.span
            key={activeChart.id}
            initial={{ scaleX: 0 }}
            animate={{ scaleX: 1 }}
            transition={{ duration: rotationIntervalMs / 1000, ease: 'linear' }}
          />
        )}
      </div>

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          className={styles.slide}
          key={activeChart.id}
          id="market-chart-panel"
          role="tabpanel"
          aria-labelledby={`market-chart-tab-${activeChart.id}`}
          initial={{ opacity: 0, x: prefersReducedMotion ? 0 : 42 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: prefersReducedMotion ? 0 : -42 }}
          transition={{ duration: prefersReducedMotion ? 0 : 0.46, ease: [0.22, 1, 0.36, 1] }}
        >
          <div className={styles.chartHeader}>
            <div>
              <span>{activeChart.name}</span>
              <strong>{activeChart.insight}</strong>
            </div>
            <div className={styles.currentState}>
              <span>현재 지수</span>
              <strong>{formatCurrentValue(activeChart)}</strong>
            </div>
          </div>

          <MarketRegimeChart chart={activeChart} />

          <div className={styles.chartFooter}>
            <span>
              <ShieldAlert size={14} aria-hidden="true" />
              {activeChart.sourceLabel} · 분석 기준 {formatDataDate(asOf)}
            </span>
            <strong>{activeChart.referenceLabel ?? '최근 10년'}</strong>
          </div>
        </motion.div>
      </AnimatePresence>

      <span className={styles.dataNotice}>{isMock ? 'UI 검증용 예시 데이터' : 'LIVE DATA'}</span>
    </aside>
  )
}
