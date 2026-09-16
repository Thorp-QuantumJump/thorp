import { ProvisionalBadge } from '@/components/paramsearch/ProvisionalBadge'
import styles from './ConditionSummaryLine.module.css'

export type SummaryPeriod = { start: string; end: string }

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
  /** 004가 등록한 비용 조건의 버전과 임시 여부. 조회할 수 없으면 `null` */
  costProfile: SummaryCostProfile | null
}

const UNAVAILABLE = '확인할 수 없음'

/**
 * 분석 기간·비교 대상·비용 적용 여부 한 줄(FR-002).
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
export function ConditionSummaryLine({ period, sectorCount, costProfile }: ConditionSummaryLineProps) {
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
        <span className={styles.label}>비용 조건</span>
        <span className={styles.value}>
          {costProfile ? costProfile.version : UNAVAILABLE}
          {costProfile?.provisional ? <ProvisionalBadge label="비용 조건" /> : null}
        </span>
      </span>
    </p>
  )
}
