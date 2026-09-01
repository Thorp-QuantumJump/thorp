import { motion } from 'motion/react'
import {
  strategyResults,
  type StrategyId,
  type StrategyResult,
} from '@/data/backtestMock'
import styles from './ResultTable.module.css'

interface ResultTableProps {
  activeStrategies: StrategyId[]
  focusedStrategy: StrategyId | null
  onFocus: (strategyId: StrategyId | null) => void
}

/** 전략 한 개의 성과 지표 셀을 동일한 순서로 렌더링한다. */
function ResultCells({ strategy }: { strategy: StrategyResult }) {
  return (
    <>
      <td className={styles.positive}>{strategy.totalReturn}</td>
      <td>{strategy.cagr}</td>
      <td className={styles.negative}>{strategy.mdd}</td>
      <td>{strategy.sharpe}</td>
      <td className={styles.optional}>{strategy.volatility}</td>
      <td className={styles.optional}>{strategy.trades}</td>
    </>
  )
}

/** 활성 전략의 결과를 비교하고 선택한 행을 차트 강조 상태와 연결한다. */
export function ResultTable({
  activeStrategies,
  focusedStrategy,
  onFocus,
}: ResultTableProps) {
  // 사용자가 비교 대상으로 선택한 전략만 결과 표에 노출한다.
  const visibleStrategies = strategyResults.filter((strategy) =>
    activeStrategies.includes(strategy.id),
  )

  return (
    <div className={styles.wrapper}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>전략</th>
            <th>누적 수익</th>
            <th>CAGR</th>
            <th>MDD</th>
            <th>Sharpe</th>
            <th className={styles.optional}>변동성</th>
            <th className={styles.optional}>거래 수</th>
          </tr>
        </thead>
        <tbody>
          {visibleStrategies.map((strategy) => {
            const isDimmed =
              focusedStrategy !== null && focusedStrategy !== strategy.id

            return (
              <motion.tr
                key={strategy.id}
                className={styles.clickableRow}
                data-active={focusedStrategy === strategy.id}
                animate={{ opacity: isDimmed ? 0.34 : 1 }}
                transition={{ duration: 0.18 }}
                onClick={() =>
                  onFocus(focusedStrategy === strategy.id ? null : strategy.id)
                }
              >
                <th scope="row">
                  <button type="button" className={styles.strategyButton}>
                    <span
                      className={styles.dot}
                      style={{ backgroundColor: strategy.color }}
                      aria-hidden="true"
                    />
                    {strategy.name}
                  </button>
                </th>
                <ResultCells strategy={strategy} />
              </motion.tr>
            )
          })}
          <tr className={styles.benchmarkRow}>
            <th scope="row">
              <span className={styles.strategyButton}>
                <span className={styles.benchmarkDot} aria-hidden="true" />
                NASDAQ-100
              </span>
            </th>
            <td className={styles.positive}>+80.2%</td>
            <td>12.5%</td>
            <td className={styles.negative}>-22.4%</td>
            <td>0.72</td>
            <td className={styles.optional}>18.5%</td>
            <td className={styles.optional}>—</td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}
