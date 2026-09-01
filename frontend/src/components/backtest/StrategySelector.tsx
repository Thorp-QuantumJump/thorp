import { Check } from 'lucide-react'
import { motion } from 'motion/react'
import { strategyResults, type StrategyId } from '@/data/backtestMock'
import styles from './StrategySelector.module.css'

interface StrategySelectorProps {
  selected: StrategyId[]
  onToggle: (strategyId: StrategyId) => void
}

/** 비교할 전략 목록과 각 전략의 선택 상태를 표시한다. */
export function StrategySelector({ selected, onToggle }: StrategySelectorProps) {
  return (
    <div className={styles.list}>
      {strategyResults.map((strategy) => {
        const isSelected = selected.includes(strategy.id)

        return (
          <motion.button
            key={strategy.id}
            type="button"
            className={styles.strategy}
            data-selected={isSelected}
            onClick={() => onToggle(strategy.id)}
            whileTap={{ scale: 0.985 }}
            aria-pressed={isSelected}
          >
            <span
              className={styles.marker}
              style={{ backgroundColor: strategy.color }}
              aria-hidden="true"
            />
            <span className={styles.copy}>
              <strong>{strategy.name}</strong>
              <small>{strategy.parameter}</small>
            </span>
            <span className={styles.check} aria-hidden="true">
              {isSelected && <Check size={14} strokeWidth={2.4} />}
            </span>
          </motion.button>
        )
      })}
    </div>
  )
}
