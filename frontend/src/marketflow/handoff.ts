import type { MarketFlowFieldId } from '@/types/marketFlow'

/**
 * 시장 흐름에서 바깥 화면으로 넘기는 값을 만든다.
 *
 * 010(경제환경 검색)과 011(전략 비교)이 아직 없으므로 **넘길 값이 무엇인지까지만** 정한다.
 * 목적지 화면과 이동 방법은 그 spec들이 소유한다. 여기서 임의의 목적지를 만들면 나중에
 * 두 벌의 계약이 생긴다.
 *
 * 값을 만들 수 없으면 `null`이다. 빈 문자열이나 대체값으로 채우지 않는다(원칙 VI).
 */

/** 010으로 넘기는 값. 분야 하나와 그 분야의 기준월 T_g다(FR-009). */
export type SimilarPeriodHandoff = {
  fieldId: MarketFlowFieldId
  /** 그 분야의 기준월 `YYYY-MM`. 분야마다 다를 수 있다. */
  referenceMonth: string
}

/** 011로 넘기는 값. 선택 섹터(있으면)와 사례 식별자다(FR-018). */
export type StrategyComparisonHandoff = {
  /** 고르지 않았으면 `null`이다. 섹터는 선택 사항이다. */
  sectorCode: string | null
  caseId: string
}

/**
 * **섹터를 받지 않는다.** 이것이 FR-016의 "섹터를 골라도 넘기는 분야·기준월은 달라지지
 * 않는다"를 지키는 방식이다. 인자에 자리가 없으면 실수로도 실을 수 없다.
 */
export function buildSimilarPeriodHandoff(field: {
  fieldId: MarketFlowFieldId
  referenceMonth: string | null
}): SimilarPeriodHandoff | null {
  if (field.referenceMonth === null) return null
  return { fieldId: field.fieldId, referenceMonth: field.referenceMonth }
}

/**
 * 사례가 없으면 넘길 것이 없다.
 *
 * 사례는 010의 검색 결과에서 고르는 값이라 010이 붙기 전에는 언제나 `null`이다. 섹터만으로
 * 전략 비교를 열면 "이 기간"이 빠진 채 넘어가므로 FR-018이 요구한 진입이 아니다.
 */
export function buildStrategyComparisonHandoff(input: {
  sectorCode: string | null
  caseId: string | null
}): StrategyComparisonHandoff | null {
  if (input.caseId === null) return null
  return { sectorCode: input.sectorCode, caseId: input.caseId }
}
