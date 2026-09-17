/**
 * `GET /sectors/evaluation-window` 응답 본문(`ApiResponse`의 `data`).
 *
 * 원본은 001 `specs/001-etf-market-data/contracts/sector-api.md`와 `evaluation-window.md`다. 기존 여섯
 * 필드와 `datasetId`에 **T098에서 001과 합의한 필드 셋**(`warmupBaseDate`·`tradingDaysFromBase`·
 * `maxWarmupTradingDays`)을 더한 모양이다. 셋은 2026-09-17 기준 001 계약에 아직 반영되지 않았다
 * (003 tasks.md T042 구현 메모).
 *
 * <p><b>화면은 워밍업 N일의 공통 시작일을 `tradingDaysFromBase[N]`으로 계산한다.</b> 그래서 값을 바꾸는
 * 동안 서버에 다시 묻지 않는다(research R7-1).
 */
export type EvaluationWindow = {
  /** 요청이 준 전략 기준의 공통 시작일. 화면은 판정에 쓰지 않고 다시 계산한다 */
  commonStart: string
  commonEnd: string
  warmupTradingDays: number
  /** 공통 시작일을 정한 후보. 모든 후보가 같은 달력을 쓰므로 워밍업 값과 무관하다 */
  bindingCandidate: string
  excludedRanges: ExcludedRange[]
  evaluable: boolean
  datasetId: string
  /** 워밍업이 0일 때의 공통 시작일 */
  warmupBaseDate: string
  /** `warmupBaseDate`부터 이어지는 거래일 오름차순 목록. 첫 원소가 `warmupBaseDate`다 */
  tradingDaysFromBase: string[]
  /** 화면이 계산할 수 있는 워밍업 상한. `tradingDaysFromBase.length - 1`과 같다 */
  maxWarmupTradingDays: number
}

export type ExcludedRange = {
  start: string
  end: string
  reason: string
}

export type EvaluationWindowQuery = {
  /** 후보 섹터 코드. 순서는 뜻이 없다 */
  sectors: string[]
  /**
   * 001 계약상 필수 인자지만 새 필드는 섹터 집합만으로 정해진다. 생략할 수 있게 해 달라고 001에 물었다
   * (T042 구현 메모 보완 질문 3, 미결). 화면은 새 필드만 쓰므로 싣지 않는다.
   */
  strategy?: string
  /** 화면이 훑을 워밍업의 상한. 등록표에서 계산해 항상 보낸다 */
  maxWarmupTradingDays: number
}
