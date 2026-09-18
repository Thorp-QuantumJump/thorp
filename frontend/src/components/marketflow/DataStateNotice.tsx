import type { FieldStatus } from '@/types/marketFlow'
import { formatUpdatedAt } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

const stateLabels: Record<FieldStatus, string> = {
  READY: '자료 확인',
  EXAMPLE: '예시 자료',
  EMPTY: '표시할 자료 없음',
  COLLECTION_FAILED: '최근 수집 실패',
}

interface DataStateNoticeProps {
  status: FieldStatus
  referenceMonth: string | null
  lastUpdatedAt: string | null
  isExampleData?: boolean
  historicalComparisonNotice?: string
  compact?: boolean
}

/** 공급 상태는 경제 판단과 구분하며 예시 여부와 기준 시점을 숨기지 않는다. */
export function DataStateNotice({
  status, referenceMonth, lastUpdatedAt, isExampleData = false,
  historicalComparisonNotice, compact = false,
}: DataStateNoticeProps) {
  return (
    <div className={styles.dataState} data-status={status} data-compact={compact}>
      <div className={styles.stateLabels}>
        <span className={styles.stateLabel}>{stateLabels[status]}</span>
        {isExampleData && status !== 'EXAMPLE' ? <span className={styles.stateLabel}>예시 자료</span> : null}
      </div>
      <dl className={styles.stateMeta}>
        <div><dt>대상월</dt><dd>{referenceMonth ?? '확인할 수 없음'}</dd></div>
        <div><dt>마지막 갱신</dt><dd>{formatUpdatedAt(lastUpdatedAt)}{lastUpdatedAt ? ' KST' : ''}</dd></div>
      </dl>
      {historicalComparisonNotice ? <p className={styles.historyNotice}>{historicalComparisonNotice}</p> : null}
    </div>
  )
}
