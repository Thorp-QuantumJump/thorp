import type { FieldStatus } from '@/types/marketFlow'
import { fieldStateLabels as stateLabels, formatUpdatedAt, stateLineLabels } from './marketFlowFormat'
import styles from './MarketFlow.module.css'


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
  status, referenceMonth, lastUpdatedAt, listUpdatedAt, isExampleData = false, showLabels = true,
}: {
  status: FieldStatus
  referenceMonth: string | null
  lastUpdatedAt: string | null
  /** 목록 머리에 적은 마지막 갱신 시각 */
  listUpdatedAt: string | null
  isExampleData?: boolean
  /** 상태 표를 이 줄에 달지. 표를 다른 자리(분야 메뉴 버튼)에 이미 달았으면 끈다 */
  showLabels?: boolean
}) {
  const labels = showLabels ? stateLineLabels(status, isExampleData) : []
  return (
    <p className={styles.stateLine} data-status={status}>
      {labels.map((label) => <span key={label} className={styles.stateLabel}>{label}</span>)}
      <span>대상월 {referenceMonth ?? '확인할 수 없음'}</span>
      {lastUpdatedAt !== listUpdatedAt ? <span>갱신 {formatUpdatedAt(lastUpdatedAt)}{lastUpdatedAt ? ' KST' : ''}</span> : null}
    </p>
  )
}

/**
 * 열린 분야 제목 줄 오른쪽의 공급 상태 표. 정상 공급이면 아무것도 달지 않는다 — 지표 칸이 그대로 보이는 것이
 * 곧 정상이라 '자료 확인'·'소속 지표 수'를 따로 적지 않는다(2026-09-29). 수집 실패·자료 없음은 빨간 '확인 중입니다',
 * 예시 자료는 늘 '예시 자료' 표를 단다(009 FR-011·FR-014).
 */
export function DataStateFacts({ status, isExampleData = false }: { status: FieldStatus; isExampleData?: boolean }) {
  const pending = status !== 'READY' && status !== 'EXAMPLE'
  const example = status === 'EXAMPLE' || isExampleData
  if (!pending && !example) return null
  return (
    <div className={styles.detailFacts} data-status={status}>
      <span className={styles.detailStates}>
        {pending ? (
          <span className={styles.stateLabel} data-tone="pending" title={stateLabels[status]}>
            확인 중입니다<span className={styles.srOnly}> ({stateLabels[status]})</span>
          </span>
        ) : null}
        {example ? <span className={styles.stateLabel}>{stateLabels.EXAMPLE}</span> : null}
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
