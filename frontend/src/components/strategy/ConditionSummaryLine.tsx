import { ProvisionalBadge } from '@/components/paramsearch/ProvisionalBadge'
import styles from './ConditionSummaryLine.module.css'

export type SummaryPeriod = { start: string; end: string }

export type SummaryAmount = {
  /** 요청에 실리는 금액 그대로 */
  value: number
  /** 등록표 `analysisConditions.initialAmount.unit`. 화면이 단위를 지어 붙이지 않는다 */
  unit?: string
}

export type SummaryCostProfile = {
  version: string
  /** 팀 확정 전 임시값인가(PD-07). 참이면 임시값 표시를 붙인다 */
  provisional: boolean
}

interface ConditionSummaryLineProps {
  /** 실제로 평가하는 기간. 아직 확인하지 못했으면 `null` */
  period: SummaryPeriod | null
  /** 002가 확정한 섹터 수 */
  sectorCount: number
  /**
   * 분석 요청에 실리는 시작 금액. 아직 모르면 `null`.
   *
   * **요청에 싣는 값과 같은 값을 넘긴다.** 사용자가 한 번도 보지 못한 금액으로 분석이 접수되지 않게
   * 하려고 이 줄에 둔다(T092, quickstart 4).
   */
  initialAmount: SummaryAmount | null
  /**
   * 004가 등록한 비용 조건의 버전과 임시 여부. 조회하는 중이면 `'loading'`, 조회할 수 없으면 `null`이다.
   *
   * 두 상태를 한 값으로 접지 않는다 — 곧 끝날 조회를 "확인할 수 없음"으로 보이면 실패한 것처럼 읽힌다.
   */
  costProfile: SummaryCostProfile | 'loading' | null
}

const UNAVAILABLE = '확인할 수 없음'
const LOADING = '확인하는 중'

/** 금액을 자릿점과 등록표의 단위로 보인다. 단위가 없으면 숫자만 보인다. */
function formatAmount(amount: SummaryAmount): string {
  return `${amount.value.toLocaleString('ko-KR')}${amount.unit ?? ''}`
}

/**
 * 분석 기간·비교 대상·시작 금액·비용 조건 한 줄(FR-002).
 *
 * <p><b>값은 props로만 받는다.</b> 이 줄은 기간을 계산하지도 비용 버전을 조회하지도 않는다. 기간은
 * 001의 공통 평가 가능 범위에서, 비용 조건은 004의 등록표에서 오며 어느 쪽도 이 컴포넌트가 아는
 * 값이 아니다.
 *
 * <p><b>모르는 값을 채우지 않는다.</b> 확인하지 못한 항목은 그 사실을 적는다. 기본 문구나 그럴듯한
 * 날짜로 대신하면 사용자가 확인되지 않은 조건을 확정된 것으로 읽는다(원칙 IV).
 *
 * <p>비용 조건이 임시값이면 글자로 그 사실을 붙인다. 색만으로 구분하지 않는다(NFR-002).
 */
export function ConditionSummaryLine({
  period,
  sectorCount,
  initialAmount,
  costProfile,
}: ConditionSummaryLineProps) {
  return (
    <p className={styles.line} aria-label="분석 조건 요약">
      <span className={styles.item}>
        <span className={styles.label}>분석 기간</span>
        <span className={styles.value}>
          {period ? `${period.start} ~ ${period.end}` : UNAVAILABLE}
        </span>
      </span>
      <span className={styles.item}>
        <span className={styles.label}>비교 대상</span>
        <span className={styles.value}>섹터 {sectorCount}개</span>
      </span>
      <span className={styles.item}>
        <span className={styles.label}>시작 금액</span>
        <span className={styles.value}>
          {initialAmount ? formatAmount(initialAmount) : UNAVAILABLE}
        </span>
      </span>
      <span className={styles.item}>
        <span className={styles.label}>비용 조건</span>
        <span className={styles.value}>
          {costProfile === 'loading'
            ? LOADING
            : costProfile
              ? costProfile.version
              : UNAVAILABLE}
          {costProfile !== 'loading' && costProfile?.provisional ? (
            <ProvisionalBadge label="비용 조건" />
          ) : null}
        </span>
      </span>
    </p>
  )
}
