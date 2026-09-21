/**
 * 011 사례 연결 REST 계약(§1·§1a·§1b·§2~§4)과 1:1로 맞춘 타입이다.
 *
 * 원본은 `specs/011-regime-strategy-link/contracts/regime-link-api.md`다.
 *
 * <p><b>여기에 없는 것이 계약의 핵심이다</b>(계약 §6). 유지율(`retention`), 전략 − KOSPI 차이·초과수익·승패,
 * 사후 성과순 정렬 키, 연율화 수익률, 내부 PK는 담을 자리를 만들지 않는다. 자리가 없으면 실수로도 화면에
 * 그릴 수 없다.
 *
 * <p>소수는 서버가 문자열로 보낸다. 숫자로 바꾸지 않고 문자열 그대로 다룬다 — 화면은 보여 주기만 하고
 * 다시 계산하지 않는다.
 */

/** 모든 응답에 붙는 근거 표기(FR-015). */
export type RegimeLinkBasis = {
  kind: 'HISTORICAL_REVISION'
  dataVersion?: string
}

/** 011 화면이 들어온 길. `MARKET_FLOW`면 필터를 걸지 않고 판정만 붙는다(FR-002). */
export type RegimeLinkEntryPath = 'MARKET_FLOW' | 'STRATEGY_RESULT'

export type RegimeLinkAvailabilityState =
  | 'NORMAL'
  | 'DATA_INSUFFICIENT'
  | 'POST_INCOMPLETE'
  | 'OUT_OF_PERIOD'

export type RegimeLinkAvailabilityReason =
  | 'UNLISTED'
  | 'WARMUP_SHORT'
  | 'SECTOR_UNMAPPED'
  | 'RECENT'
  | 'DELISTED'

export type RegimeLinkScenario = 'CONTINUOUS' | 'FRESH_CASH'

export type RegimeLinkStatus =
  | 'PENDING_RUN'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'

/**
 * 사례의 사후 구간. `d21`·`d63`은 판정 기준일을 넘어가면 **명시적 `null`**이고 그때
 * `completedTradingDays`가 진짜 길이다.
 *
 * `lastAvailable`은 §1a 응답에만 있다(§1 목록에는 싣지 않는다).
 */
export type RegimeLinkPostWindow = {
  d0: string
  d21: string | null
  d63: string | null
  lastAvailable?: string | null
  completedTradingDays: number
  complete21: boolean
  complete63: boolean
}

export type RegimeLinkAvailability = {
  state: RegimeLinkAvailabilityState
  reason?: RegimeLinkAvailabilityReason
  /** 구간 안 상장폐지이지만 21거래일 이상 확보한 경우에만 참이다. */
  partial?: boolean
  delistedOn?: string
}

/** §1a `GET /regime-links/case-window?caseId=` 응답. 003이 분석 기간 제약에 쓴다. */
export type RegimeLinkCaseWindow = {
  basis: RegimeLinkBasis
  caseId: string
  endMonth: string
  postWindow: RegimeLinkPostWindow
}

export type RegimeLinkRunRole = 'BASE' | 'COMPARE'

export type RegimeLinkRun = {
  publicToken: string
  role: RegimeLinkRunRole
  strategyId: string
  strategyName: string
  period: { start: string; end: string; effectiveStart?: string }
  warmupTradingDays: number
}

export type RegimeLinkInstrument = {
  sectorId?: string
  instrumentId: string
  listedOn: string
  /** 폐지된 종목에만 있다. 키가 없으면 상장이 유지되고 있다는 뜻이다. */
  delistedOn?: string
}

export type RegimeLinkFilter = {
  applied: boolean
  filterKey?: string
  /** 필터를 건 응답에는 범위가 비어도 키가 남고 양끝이 `null`이다. */
  availableRange?: { firstCaseEndMonth: string | null; lastCaseEndMonth: string | null }
  excludedCount?: number
  minPostTradingDays: number
}

export type RegimeLinkRunState = {
  publicToken: string
  state: RegimeLinkAvailabilityState
  reason?: RegimeLinkAvailabilityReason
  partial?: boolean
  delistedOn?: string
  /** 그 실행이 실제로 확보한 거래일 수. 시장 달력의 `postWindow`와 다를 수 있다. */
  completedTradingDays: number
}

export type RegimeLinkCase = {
  caseId: string
  endMonth: string
  rank: number
  /** 소수는 문자열 그대로다. */
  distance: string
  postWindow: RegimeLinkPostWindow
  availability: RegimeLinkAvailability
  perRun: RegimeLinkRunState[]
  shownInFiltered: boolean
}

export type RegimeLinkSearchInfo = {
  snapshotKey: string
  mode?: string
  field?: string
  baselineMonth?: string
  status?: string
}

/** §1 `GET /regime-links/availability` 응답. */
export type RegimeLinkAvailabilityResult = {
  basis: RegimeLinkBasis
  search: RegimeLinkSearchInfo
  bundle: { runs: RegimeLinkRun[]; instruments: RegimeLinkInstrument[] }
  filter: RegimeLinkFilter
  cases: RegimeLinkCase[]
}

export type RegimeLinkAvailabilityQuery = {
  base: string
  compare?: string
  snapshotKey?: string
  mode?: 'field' | 'composite'
  field?: string
  baselineMonth?: string
  entryPath: RegimeLinkEntryPath
}

/** §1b 요청. `clientRequestId`는 같은 본문 재시도를 하나로 묶는 멱등 키다. */
export type RegimeLinkSearchCreateRequest = {
  clientRequestId: string
  snapshotKey: string
  base: string
  compare?: string
  entryPath: RegimeLinkEntryPath
  fullSearchShown: boolean
  previousLinkSearchId?: string
}

/** 비교 묶음이 달라졌을 때만 온다. */
export type RegimeLinkBundleChanged = {
  added: string[]
  removed: string[]
  excludedCount: number
}

/** §1b 응답. §1 응답에 `linkSearchId`와 변경 알림을 더한 모양이다. */
export type RegimeLinkSearchCreated = RegimeLinkAvailabilityResult & {
  linkSearchId: string
  bundleChanged?: RegimeLinkBundleChanged
}

/** §2 요청. */
export type RegimeLinkCreateRequest = {
  linkSearchId: string
  caseId: string
  scenario: RegimeLinkScenario
  confirmFullPeriod: boolean
}

export type RegimeLinkAccepted = {
  basis: RegimeLinkBasis
  linkSearchId: string
  links: {
    linkId: string
    publicToken: string
    scenario: RegimeLinkScenario
    status: RegimeLinkStatus
  }[]
}

/**
 * §2의 200 응답. 계산을 만들지 않고 확인을 받는다(Clarifications 09-18 Q1).
 *
 * 접수(202)와 한 union으로 두어, 부르는 쪽이 `outcome`을 보지 않고 결과를 쓰지 못하게 한다.
 */
export type RegimeLinkConfirmRequired = {
  outcome: 'OUT_OF_PERIOD_CONFIRM_REQUIRED'
  requiredRange: { start: string; end: string }
  affected: { publicToken: string; period: { start: string; end: string } }[]
}

export type RegimeLinkCreateOutcome = RegimeLinkConfirmRequired | (RegimeLinkAccepted & { outcome?: undefined })

/** 004 실행이 아직 끝나지 않은 링크의 진행 상태(005 비교 대상과 같은 표시). */
export type RegimeLinkRunProgress = {
  status: string
  progress?: number | null
  queueWaitSeconds?: number | null
}

export type RegimeLinkFailure = {
  code: string
  message: string
  /** 거짓이면 '다시 시도'를 두지 않는다. */
  retryable: boolean
}

export type RegimeLinkCosts = {
  commission: string
  spread: string
  slippage: string
  tax: string
  total: string
}

export type RegimeLinkWindowResult = {
  tradingDays: number
  return: string
  maxDrawdown: string
  tradeCount: number
  tradesPerMonth: string
  turnover: string
  turnoverBasis: string
  costs: RegimeLinkCosts
  lossContribution: Record<string, string>
  complete: boolean
}

export type RegimeLinkHolding = { date: string; actual: Record<string, string> }

export type RegimeLinkTrade = {
  date: string
  instrumentId: string
  side: 'BUY' | 'SELL'
  quantity: number
  price: string
  cost: string
  reason: string
}

export type RegimeLinkKospi = {
  window63?: { return: string; maxDrawdown: string }
  window21?: { return: string; maxDrawdown: string }
}

export type RegimeLinkResult = {
  window63: RegimeLinkWindowResult | null
  window21: RegimeLinkWindowResult | null
  holdings: RegimeLinkHolding[]
  trades: RegimeLinkTrade[]
  kospi: RegimeLinkKospi
}

/** §3 `GET /regime-links/{linkId}?token=` 응답. §4는 이 모양의 배열이다. */
export type RegimeLinkView = {
  basis: RegimeLinkBasis
  linkId: string
  publicToken: string
  caseId: string
  scenario: RegimeLinkScenario
  status: RegimeLinkStatus
  postWindow: RegimeLinkPostWindow
  availability: RegimeLinkAvailability
  runProgress: RegimeLinkRunProgress | null
  failure: RegimeLinkFailure | null
  result: RegimeLinkResult | null
  queuedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export type RegimeLinkRetryAccepted = {
  linkId: string
  status: RegimeLinkStatus
}
