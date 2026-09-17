import type { PeriodReduction } from './analysisSupport'

export type ReducedPeriodInput = {
  /** 워밍업을 채운 뒤 평가할 수 있는 공통 범위 */
  window: { start: string; end: string }
  reduction: PeriodReduction
  /** `reduction.bindingCandidate`를 사용자가 읽을 이름. 찾지 못하면 호출자가 식별자를 그대로 넘긴다 */
  bindingName: string
  /** 지금 설정으로 계산한 워밍업(거래일) */
  warmupTradingDays: number
  /** 워밍업을 이루는 항목. 없으면 워밍업이라고만 적는다 */
  causes: readonly WarmupCause[]
}

/**
 * 워밍업을 이루는 항목 하나와 지금 값(T066의 "현재 관측 기간·최근 제외 기간").
 *
 * 이름과 단위는 등록표 `warmupRule.sum`이 가리키는 축의 `label`·`unit`을 그대로 넘긴다. 화면이 "관측 기간"
 * 같은 낱말을 전략마다 적어 두지 않는다(FR-005).
 */
export type WarmupCause = { label: string; value: number; unit?: string }

/**
 * 줄어든 평가 구간과 그 원인 한 문장(FR-011, SC-004).
 *
 * <p><b>선택 카드와 '분석하기' 바로 위가 이 함수 하나를 함께 쓴다.</b> SC-004는 두 곳 모두에 줄어든
 * 기간과 원인이 보이기를 요구한다. 문장을 따로 지으면 한쪽만 고쳐져 두 곳이 다른 말을 하게 된다.
 */
export function describeReducedPeriod({
  window,
  reduction,
  bindingName,
  warmupTradingDays,
  causes,
}: ReducedPeriodInput): string {
  const parts = causes.map((item) => `${item.label} ${item.value}${item.unit ?? ''}`)
  const cause =
    parts.length > 0
      ? `지금 설정의 ${parts.join('·')}(워밍업 ${warmupTradingDays}거래일)`
      : `워밍업 ${warmupTradingDays}거래일`
  return (
    `${bindingName}의 자료가 늦게 시작하는 데다 ${cause}을 먼저 채워야 해서, ` +
    `평가할 수 있는 기간이 ${reduction.startWithoutWarmup}부터가 아니라 ${window.start} ~ ${window.end}로 줄었습니다.`
  )
}
