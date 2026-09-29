import { motion, useReducedMotion } from 'motion/react'
import { useId } from 'react'
import type { FieldSummary } from '@/types/marketFlow'
import { DataStateLine } from './DataStateNotice'
import { stateLineLabels } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

interface FieldSummaryCardProps {
  field: FieldSummary
  selected?: boolean
  onOpen: (field: FieldSummary) => void
  /** 목록 머리에 적은 마지막 갱신 시각. 이 분야의 갱신 시각이 같으면 줄에서 되풀이하지 않는다 */
  listUpdatedAt?: string | null
}

/**
 * 분야 하나를 여는 메뉴 버튼. 일곱 개가 한 줄에 따로따로 선다.
 *
 * 변화 요약과 대상월은 버튼을 가리키거나 키보드 초점을 줄 때 그 아래에 뜬다. 버튼이 설명으로 연결해 두어
 * 보조기기는 뜨지 않아도 읽는다. 고른 분야의 요약은 아래 상세 머리에 늘 펼쳐 있다(009 FR-002).
 * 수집 실패·자료 없음·예시 자료 표는 버튼 위에 늘 보인다(FR-011·FR-014).
 */
export function FieldSummaryCard({ field, selected = false, onOpen, listUpdatedAt = null }: FieldSummaryCardProps) {
  const reducedMotion = useReducedMotion()
  const tipId = useId()
  const labels = stateLineLabels(field.status, field.isExampleData)
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
          aria-describedby={tipId}
          onClick={() => onOpen(field)}
        >
          <span>{field.displayName}</span>
          {labels.map((label) => <span key={label} className={styles.stateLabel}>{label}</span>)}
        </button>
      </h3>
      <div id={tipId} role="tooltip" className={styles.fieldTip}>
        <p className={styles.narrative}>{field.narrative ?? '변화 요약 대신 자료 상태를 확인해 주세요.'}</p>
        <DataStateLine
          status={field.status}
          referenceMonth={field.referenceMonth}
          lastUpdatedAt={field.lastUpdatedAt}
          listUpdatedAt={listUpdatedAt}
          isExampleData={field.isExampleData}
          showLabels={false}
        />
      </div>
    </motion.article>
  )
}
