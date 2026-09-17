/**
 * `GET /sectors/evaluation-window` 응답 본문(`ApiResponse`의 `data`).
 *
 * 원본은 001 `specs/001-etf-market-data/contracts/sector-api.md`의 「GET /sectors/evaluation-window」 절이다.
 * `evaluation-window.md`의 출력 여섯 칸과 `datasetId`에, 조회 계층이 더한 `bindingSector`와 재판정 재료
 * 네 칸(`warmupBaseDate`·`tradingDaysFromBase`·`maxWarmupTradingDays`·`excludedRangesFromBase`)을 더한
 * 모양이다(T098 합의, 001 MR !183). 화면은 `maxWarmupTradingDays`를 늘 보내므로 재료 네 칸이 늘 온다.
 *
 * <p><b>화면은 워밍업 N일의 공통 시작일을 `tradingDaysFromBase[N]`으로 계산한다.</b> 그래서 값을 바꾸는
 * 동안 서버에 다시 묻지 않는다(research R7-1).
 */
export type EvaluationWindow = {
  /**
   * 요청이 준 전략 기준의 공통 시작일. 화면은 판정에 쓰지 않고 다시 계산한다. 워밍업을 채울 거래일이
   * 모자라면 `null`이다(키는 남는다)
   */
  commonStart: string | null
  commonEnd: string
  warmupTradingDays: number
  /** 공통 시작일을 정한 후보. 모든 후보가 같은 달력을 쓰므로 워밍업 값과 무관하다 */
  bindingCandidate: string
  /** `bindingCandidate`가 대표하는 섹터 코드 */
  bindingSector: string
  /** 워밍업을 반영한 `[commonStart, commonEnd]` 안의 자료 부족 구간(워커와 공유하는 산식의 출력) */
  excludedRanges: ExcludedRange[]
  evaluable: boolean
  datasetId: string
  /** 워밍업이 0일 때의 공통 시작일 */
  warmupBaseDate: string
  /**
   * `warmupBaseDate`부터 `commonEnd`까지의 거래일 오름차순 목록. 비어 있지 않으면 첫 원소가 `warmupBaseDate`다.
   * **기준일이 이미 `commonEnd`를 넘으면 빈 배열이다** — 어떤 워밍업으로도 평가할 수 없다는 뜻이다
   */
  tradingDaysFromBase: string[]
  /**
   * 화면이 계산할 수 있는 워밍업 상한. `tradingDaysFromBase.length - 1`과 같다. 달력이 모자라면 요청한 상한보다
   * 작다. 목록이 비었으면 001이 키를 싣지 않으며 여기서는 `null`이다
   */
  maxWarmupTradingDays: number | null
  /** `[warmupBaseDate, commonEnd]` 안의 자료 부족 구간. 화면이 더 짧은 워밍업으로 계산할 때 쓴다 */
  excludedRangesFromBase: ExcludedRange[]
}

export type ExcludedRange = {
  start: string
  end: string
  reason: string
}

export type EvaluationWindowQuery = {
  /**
   * 후보 섹터 코드. **순서를 보존한다** — 001 산식의 `bindingCandidate` 동점 규칙이 입력 순서를 쓰므로,
   * 접수 요청의 섹터 순서와 같아야 화면이 안내한 원인과 접수 경로의 판정이 갈리지 않는다
   */
  sectors: string[]
  /**
   * 생략하면 001이 워밍업 0으로 판정한다. 재판정 재료는 섹터 집합만으로 정해지므로 화면은 싣지 않고, 카드
   * 여러 장을 요청 한 번으로 판정한다(001 답변, 2026-09-17).
   */
  strategy?: string
  /** 화면이 훑을 워밍업의 상한. 등록표에서 계산해 항상 보낸다. 001은 0 이상 6000 이하만 받는다 */
  maxWarmupTradingDays: number
}
