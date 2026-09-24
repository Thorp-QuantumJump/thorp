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
}

/**
 * 목록 한 줄용 짧은 표시(009 FR-011). 대상월은 늘 보이고, 마지막 갱신은 목록 머리에 한 번 적으므로 그와 다를
 * 때만 적는다. 상태 표는 정상 공급('자료 확인')이면 달지 않는다 — 일곱 줄이 모두 같은 표를 달면 읽을 것이
 * 없고, FR-011은 수집 실패·자료 없음만 상태로 보이라고 한다. 예시 자료는 늘 밝힌다(FR-014).
 */
export function DataStateLine({
  status, referenceMonth, lastUpdatedAt, listUpdatedAt, isExampleData = false,
}: {
  status: FieldStatus
  referenceMonth: string | null
  lastUpdatedAt: string | null
  /** 목록 머리에 적은 마지막 갱신 시각 */
  listUpdatedAt: string | null
  isExampleData?: boolean
}) {
  const labels = [
    status === 'READY' ? null : stateLabels[status],
    isExampleData && status !== 'EXAMPLE' ? stateLabels.EXAMPLE : null,
  ].filter((label): label is string => label !== null)
  return (
    <p className={styles.stateLine} data-status={status}>
      {labels.map((label) => <span key={label} className={styles.stateLabel}>{label}</span>)}
      <span>대상월 {referenceMonth ?? '확인할 수 없음'}</span>
      {lastUpdatedAt !== listUpdatedAt ? <span>갱신 {formatUpdatedAt(lastUpdatedAt)}{lastUpdatedAt ? ' KST' : ''}</span> : null}
    </p>
  )
}

/**
 * 열린 분야 제목 줄 오른쪽. 소속 지표 수와 그 밑의 공급 상태 표만 둔다 — 표 글자가 곧 상태라 '자료 상태' 같은
 * 이름을 따로 달지 않는다. 대상월·마지막 갱신은 분야 설명 밑 한 줄에 적는다(009 FR-011·013·014).
 * 예시 자료는 늘 '예시 자료' 표를 함께 단다(FR-014).
 */
export function DataStateFacts({
  indicatorCount, status, isExampleData = false,
}: {
  indicatorCount: number
  status: FieldStatus
  isExampleData?: boolean
}) {
  return (
    <div className={styles.detailFacts} data-status={status}>
      <span className={styles.detailCount}>소속 지표 <b>{indicatorCount}</b></span>
      <span className={styles.detailStates}>
        {/* 정상 공급이면 '자료 확인'(파랑), 수집 실패·자료 없음이면 '확인 중입니다'(빨강). 까닭은 읽을 수 있게 남긴다. */}
        {status === 'READY' || status === 'EXAMPLE' ? (
          <span className={styles.stateLabel} data-tone="confirmed">{stateLabels.READY}</span>
        ) : (
          <span className={styles.stateLabel} data-tone="pending" title={stateLabels[status]}>
            확인 중입니다<span className={styles.srOnly}> ({stateLabels[status]})</span>
          </span>
        )}
        {status === 'EXAMPLE' ? <span className={styles.stateLabel}>{stateLabels.EXAMPLE}</span> : null}
        {isExampleData && status !== 'EXAMPLE' ? <span className={styles.stateLabel}>{stateLabels.EXAMPLE}</span> : null}
      </span>
    </div>
  )
}

/** 공급 상태는 경제 판단과 구분하며 예시 여부와 기준 시점을 숨기지 않는다. */
export function DataStateNotice({
  status, referenceMonth, lastUpdatedAt, isExampleData = false,
  historicalComparisonNotice,
}: DataStateNoticeProps) {
  return (
    <div className={styles.dataState} data-status={status}>
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
