import { ArrowUpRight } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { FieldSummary } from '@/types/marketFlow'
import { DataStateNotice } from './DataStateNotice'
import styles from './MarketFlow.module.css'

interface FieldSummaryCardProps {
  field: FieldSummary
  selected?: boolean
  onOpen: (field: FieldSummary) => void
}

/** 기존 호출부 이름은 유지하되, 카드를 얇은 선택 행으로 표현한다. */
export function FieldSummaryCard({ field, selected = false, onOpen }: FieldSummaryCardProps) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.article
      className={styles.summaryCard}
      data-selected={selected}
      initial={reducedMotion ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.22, delay: reducedMotion ? 0 : (field.displayOrder - 1) * 0.025 }}
    >
      {selected ? (
        <motion.span
          className={styles.selectionLine}
          layoutId={reducedMotion ? undefined : 'market-flow-selection'}
          transition={{ duration: 0.2 }}
          aria-hidden="true"
        />
      ) : null}
      <h3 className={styles.summaryHeading}>
        <button
          type="button"
          className={styles.openButton}
          aria-label={`${field.displayName} 분야 열기`}
          aria-pressed={selected}
          aria-controls="market-flow-detail"
          onClick={() => onOpen(field)}
        >
          <span className={styles.fieldNumber} aria-hidden="true">{String(field.displayOrder).padStart(2, '0')}</span>
          <span>{field.displayName}</span>
          <ArrowUpRight size={16} aria-hidden="true" />
        </button>
      </h3>
      <p className={styles.narrative}>{field.narrative ?? '변화 요약 대신 아래 자료 상태를 확인해 주세요.'}</p>
      <DataStateNotice
        status={field.status}
        referenceMonth={field.referenceMonth}
        lastUpdatedAt={field.lastUpdatedAt}
        isExampleData={field.isExampleData}
        compact
      />
    </motion.article>
  )
}
