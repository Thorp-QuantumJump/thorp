import {
  ArrowRight,
  ChartNoAxesCombined,
  CircleDollarSign,
  Play,
  RotateCcw,
  SlidersHorizontal,
} from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { BacktestChart } from '@/components/backtest/BacktestChart'
import { ResultTable } from '@/components/backtest/ResultTable'
import { StrategySelector } from '@/components/backtest/StrategySelector'
import { AppHeader } from '@/components/navigation/AppHeader'
import {
  strategyResults,
  type StrategyId,
  type StrategyResult,
} from '@/data/backtestMock'
import styles from './BacktestPage.module.css'

type BacktestViewStatus = 'loading' | 'ready'

const initialStrategies: StrategyId[] = [
  'trend',
  'dualMomentum',
  'rsiMeanReversion',
]

/** 백테스트 조건, 전략 비교, 실행 상태와 결과 화면을 조합한다. */
export function BacktestPage() {
  const [activeStrategies, setActiveStrategies] =
    useState<StrategyId[]>(initialStrategies)
  const [focusedStrategy, setFocusedStrategy] = useState<StrategyId | null>(null)
  const [status, setStatus] = useState<BacktestViewStatus>('ready')
  const [progress, setProgress] = useState(100)
  const [runIndex, setRunIndex] = useState(1)
  const [initialCapital, setInitialCapital] = useState(10_000_000)

  // 프로토타입 실행 시간을 재현하고 완료 시 차트를 ready 상태로 전환한다.
  useEffect(() => {
    if (status !== 'loading') return

    const startedAt = Date.now()
    const duration = 1600
    const interval = window.setInterval(() => {
      const nextProgress = Math.min(
        94,
        Math.round(((Date.now() - startedAt) / duration) * 100),
      )
      setProgress(nextProgress)
    }, 80)

    const timeout = window.setTimeout(() => {
      window.clearInterval(interval)
      setProgress(100)
      setStatus('ready')
    }, duration)

    return () => {
      window.clearInterval(interval)
      window.clearTimeout(timeout)
    }
  }, [status, runIndex])

  // 선택하거나 강조한 전략을 핵심 성과 지표의 기준으로 사용한다.
  const summaryStrategy = useMemo<StrategyResult>(() => {
    const preferredId = focusedStrategy ?? activeStrategies[0]
    return (
      strategyResults.find((strategy) => strategy.id === preferredId) ??
      strategyResults[0]
    )
  }, [activeStrategies, focusedStrategy])

  // 비교 전략을 추가·제거하되 최소 한 개의 전략은 유지한다.
  const toggleStrategy = (strategyId: StrategyId) => {
    setActiveStrategies((current) => {
      const isSelected = current.includes(strategyId)
      if (isSelected && current.length === 1) return current

      return isSelected
        ? current.filter((id) => id !== strategyId)
        : [...current, strategyId]
    })

    if (focusedStrategy === strategyId) setFocusedStrategy(null)
  }

  // 이전 강조 상태를 초기화하고 새 백테스트 실행 연출을 시작한다.
  const runBacktest = () => {
    if (status === 'loading') return
    setFocusedStrategy(null)
    setProgress(0)
    setRunIndex((current) => current + 1)
    setStatus('loading')
  }

  return (
    <main className={styles.page}>
      <AppHeader />

      <div className={styles.container}>
        <div className={styles.workspace}>
          <div className={styles.mainColumn}>
            {/* 선택한 전략과 벤치마크의 누적 성과를 같은 축에서 비교한다. */}
            <section className={`${styles.panel} ${styles.chartPanel}`}>
              <div className={styles.panelHeader}>
                <div>
                  <div className={styles.titleLine}>
                    <ChartNoAxesCombined size={17} aria-hidden="true" />
                    <h2>포트폴리오 성장</h2>
                  </div>
                  <p>초기값 100으로 정규화 · 수수료 반영 예시 데이터</p>
                </div>
                <div className={styles.legend} aria-label="차트 범례">
                  {strategyResults
                    .filter((strategy) => activeStrategies.includes(strategy.id))
                    .map((strategy) => (
                      <span key={strategy.id}>
                        <i style={{ background: strategy.color }} aria-hidden="true" />
                        {strategy.name}
                      </span>
                    ))}
                  <span>
                    <i className={styles.benchmarkMarker} aria-hidden="true" />
                    KOSPI 200
                  </span>
                </div>
              </div>

              <div className={styles.chartStage} aria-busy={status === 'loading'}>
                <BacktestChart
                  activeStrategies={activeStrategies}
                  focusedStrategy={focusedStrategy}
                  status={status}
                  revealSignature={`run-${runIndex}`}
                  initialCapital={initialCapital}
                />
                <AnimatePresence>
                  {status === 'loading' && (
                    <motion.div
                      className={styles.progressBadge}
                      initial={{ opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -6 }}
                      role="status"
                      aria-live="polite"
                    >
                      <span>백테스트 실행 중</span>
                      <strong>{progress}%</strong>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </section>

            {/* 현재 기준 전략의 핵심 성과 지표를 요약한다. */}
            <section className={styles.metricGrid} aria-label="핵심 성과 지표">
              {[
                ['누적 수익률', summaryStrategy.totalReturn, 'positive'],
                ['CAGR', summaryStrategy.cagr, 'positive'],
                ['최대 낙폭', summaryStrategy.mdd, 'negative'],
                ['Sharpe Ratio', summaryStrategy.sharpe, 'neutral'],
              ].map(([label, value, tone], index) => (
                <motion.article
                  layout
                  key={label}
                  className={styles.metric}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.035 }}
                >
                  <span>{label}</span>
                  <strong data-tone={tone}>{value}</strong>
                </motion.article>
              ))}
            </section>

            {/* 결과 행 선택과 차트 강조 상태를 연결한다. */}
            <section className={`${styles.panel} ${styles.resultPanel}`}>
              <div className={styles.resultHeader}>
                <div>
                  <h2>전략별 결과 비교</h2>
                  <p>전략 행을 누르면 차트에서도 해당 전략이 강조됩니다.</p>
                </div>
                {focusedStrategy && (
                  <button
                    type="button"
                    className={styles.resetButton}
                    onClick={() => setFocusedStrategy(null)}
                  >
                    <RotateCcw size={14} aria-hidden="true" />
                    전체 보기
                  </button>
                )}
              </div>
              <ResultTable
                activeStrategies={activeStrategies}
                focusedStrategy={focusedStrategy}
                onFocus={setFocusedStrategy}
              />
            </section>
          </div>

          {/* 비교 대상 모두에 공통으로 적용할 백테스트 조건을 입력한다. */}
          <aside className={`${styles.panel} ${styles.configPanel}`}>
            <div className={styles.configTitle}>
              <SlidersHorizontal size={17} aria-hidden="true" />
              <div>
                <h2>백테스트 설정</h2>
                <p>공통 조건과 비교 전략을 선택하세요.</p>
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <label htmlFor="market">투자 대상</label>
              <select id="market" defaultValue="kospi200">
                <option value="kospi200">KOSPI 200</option>
              </select>
            </div>

            <div className={styles.dateGrid}>
              <div className={styles.fieldGroup}>
                <label htmlFor="start-date">시작일</label>
                <input id="start-date" type="date" defaultValue="2019-01-01" />
              </div>
              <div className={styles.fieldGroup}>
                <label htmlFor="end-date">종료일</label>
                <input id="end-date" type="date" defaultValue="2023-12-31" />
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <label htmlFor="capital">초기 투자금</label>
              <div className={styles.inputIconWrap}>
                <CircleDollarSign size={16} aria-hidden="true" />
                <select
                  id="capital"
                  value={initialCapital}
                  onChange={(event) => setInitialCapital(Number(event.target.value))}
                >
                  <option value="1000000">1,000,000원</option>
                  <option value="10000000">10,000,000원</option>
                  <option value="100000000">100,000,000원</option>
                </select>
              </div>
            </div>

            <div className={styles.divider} />

            <div className={styles.strategyHeading}>
              <div>
                <span>비교 전략</span>
                <small>최대 {strategyResults.length}개</small>
              </div>
              <strong>{activeStrategies.length}/{strategyResults.length}</strong>
            </div>
            <StrategySelector selected={activeStrategies} onToggle={toggleStrategy} />

            <button
              type="button"
              className={styles.runButton}
              onClick={runBacktest}
              disabled={status === 'loading'}
            >
              <Play size={16} fill="currentColor" aria-hidden="true" />
              {status === 'loading' ? `계산 중 ${progress}%` : '백테스트 실행'}
            </button>
            <p className={styles.configNote}>
              현재 화면의 결과는 UI 검증을 위한 고정 예시 데이터입니다.
            </p>

            {/*
              설정 조합 자동 비교(spec 007)로 넘어가는 진입점이다. 대상 섹터·전략은 질의 문자열로
              넘기는데, 이 화면의 전략은 아직 예시 데이터라 등록표의 전략 ID가 아니다 — 그래서
              지금은 대상 없이 열리고 그 화면이 들어온 경로를 안내한다. 전략 화면이 등록표를
              읽게 되면 여기서 고른 값을 그대로 붙인다.
            */}
            <Link to="/param-search" className={styles.autoSearchLink}>
              설정 조합 자동 비교
              <ArrowRight size={14} aria-hidden="true" />
            </Link>
          </aside>
        </div>
      </div>
    </main>
  )
}
