/**
 * 공개 결과 열람(`/results/{token}`, spec 005)의 응답 타입.
 *
 * 원본 계약은 `specs/005-result-comparison/contracts/result-api.md`이고 요약은
 * `docs/specs/result-api/README.md`다. 예시 파일(`docs/specs/result-api/examples/*.json`)이 기준이며
 * `src/test/resultFixtures.test.ts`가 이 타입으로 만든 픽스처와 키를 대조한다.
 *
 * **금액과 비율은 문자열이다.** 004 계약과 같다(`types/backtestRequest.ts` 머리 주석). 화면은 자릿수만
 * 다듬고 값을 다시 계산하지 않는다.
 *
 * **`backtestId`를 이 타입에 두지 않는다(SC-009).** 공개 응답에는 004의 내부 실행 식별자가 없고, 화면은
 * 공개 토큰만 들고 다닌다. 필드를 더하다 식별자가 섞이면 결과 주소나 DOM에 드러난다.
 */

export type ResultStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

/** PD-12 확정 전에는 항상 `UNVALIDATED`다. '검증 통과'가 아니라 '말할 수 없음'이다 */
export type ValidationTier = 'UNVALIDATED' | (string & Record<never, never>)

/** 004 `summaryUnavailable`. 새 사유가 더해져도 버리지 않도록 열어 둔다 */
export type SummaryUnavailableReason =
  | 'CANCELLED_WHILE_RUN_CONTINUED'
  | 'CANCELLED_BEFORE_START'
  | 'CANCEL_UNANSWERED'
  | (string & Record<never, never>)

export type WeightsSource = 'USER' | 'COMPUTED'

export type ResultProgress = {
  stage: 'PREPARING_DATA' | 'COMPUTING' | 'SAVING' | (string & Record<never, never>)
  lastTradingDate: string | null
  completedTradingDays: number | null
  totalTradingDays: number | null
}

export type ResultConfigItem = {
  sectorId: string
  instrumentId: string
  targetWeight?: string
}

/** 접수가 저장한 요청 사본에 `currency`·`effectiveStart`·`effectiveEnd`를 얹은 것이다 */
export type ResultConfig = {
  executionType?: string
  registryVersion?: string
  scenarioParams?: Record<string, unknown>
  strategyId: string
  rulesVersion: string
  parameters: Record<string, unknown>
  items: ResultConfigItem[]
  mappingVersion: string
  start: string
  end: string
  effectiveStart: string
  effectiveEnd: string
  initialCash: string
  currency: string
  warmupTradingDays: number
  scenario: string
  evaluationSplitVersion: string | null
}

export type VersionMark = {
  version: string
  provisional: boolean
}

/** 004 `versions`에서 워커 자기 보고(`worker`)를 뺀 것이다 */
export type ResultVersions = {
  engine: string | null
  rules: VersionMark | null
  data: string | null
  evaluationSplit: string | null
  costProfile: VersionMark | null
  mapping: string | null
}

export type ResultLiquidation = {
  markToMarket: { finalEquity: string; totalReturn: string }
  liquidated: { finalEquity: string; totalReturn: string; liquidationCost: string }
}

export type ResultSummary = {
  initialEquity: string
  finalEquity: string
  currency: string
  totalReturn: string
  cagr: string | null
  /** 운용 중 고점 대비 최대 하락. 0 이하의 비율이다 */
  maxDrawdown: string
  sharpeRatio: string | null
  volatility: string | null
  tradeCount: number
  winRate: string | null
  totalCommission: string
  totalSlippageCost: string
  /** 비용 차감 후 전체 수익률. 핵심 숫자 하나 */
  netTotalReturn: string
  /** 거래비용 합계. 핵심 숫자 하나 */
  totalTradingCost: string
  costs: { commission: string; spread: string; slippage: string; tax: string; total: string }
  /** 요청이 청산을 끄면 `null`이다 */
  liquidation: ResultLiquidation | null
  analytics: { recoveryDays: number | null; turnover: string | null } | null
  exceptionCount: number
}

export type ResultFailure = {
  code: string
  message: string
}

/** `GET /results/{token}` */
export type PublicResultDetail = {
  publicToken: string
  status: ResultStatus
  resultSource: string
  partial: boolean
  cancelRequested: boolean
  /** 비로그인 결과의 만료 시각. 로그인 결과는 `null` */
  expiresAt: string | null
  /** `QUEUED`·`RUNNING`일 때만 있다 */
  progress: ResultProgress | null
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
  /** `QUEUED`·`RUNNING`일 때만 있다 */
  queueWaitSeconds: number | null
  config: ResultConfig
  versions: ResultVersions
  summary: ResultSummary | null
  summaryUnavailable: SummaryUnavailableReason | null
  failure: ResultFailure | null
  validationTier: ValidationTier
  /** 003 등록표의 전략 이름. 없으면 `null`이고 화면은 이름을 지어내지 않는다 */
  strategyName: string | null
  /** 볼 결과가 없으면 `null` */
  weightsSource: WeightsSource | null
  /** 예외 `kind`의 중복 없는 목록. 볼 결과가 없으면 `null`, 빈 목록은 "예외 없음" */
  exceptionKinds: string[] | null
}

export type ResultCashFlow = {
  type: string
  instrumentId?: string
  amount: string
}

export type ResultTimeseriesPoint = {
  date: string
  equity: string
  cumulativeReturn: string
  drawdown: string
  /** 벤치마크가 없거나 그날 값이 없으면 키가 없다 */
  benchmarkReturn?: string
  /** 현금 흐름이 있는 날에만 있다 */
  cashFlows?: ResultCashFlow[]
}

/** `GET /results/{token}/timeseries` */
export type ResultTimeseries = {
  partial: boolean
  points: ResultTimeseriesPoint[]
}

export type ResultTrade = {
  tradeId: string
  instrumentId: string
  side: string
  status: 'OPEN' | 'CLOSED'
  entryDate: string
  exitDate: string | null
  entryPrice: string
  exitPrice: string | null
  quantity: string
  grossPnl: string | null
  commission: string
  slippageCost: string
  realizedPnl: string | null
  returnRate: string | null
  reason: string
  plannedDate?: string
}

/** `GET /results/{token}/trades` */
export type ResultTradePage = {
  /** 하위 자원 다섯 개가 모두 싣는다(004 README §2). 취소·한도로 끊긴 부분 결과라는 뜻이다 */
  partial: boolean
  page: number
  size: number
  totalElements: number
  totalPages: number
  content: ResultTrade[]
}

/** `GET /results/{token}/positions?date=` */
export type ResultPositions = {
  partial: boolean
  requestedDate: string
  snapshotDate: string
  positions: {
    instrumentId: string
    quantity: string
    marketValue: string
    weight: string
    unrealizedPnl: string
  }[]
}

/** `GET /results/{token}/weights` */
export type ResultWeights = {
  partial: boolean
  weightsSource: WeightsSource
  points: {
    date: string
    target: Record<string, string>
    actual: Record<string, string>
    weightError: string
    riskScale?: string
    estimatedVolatility?: string
  }[]
}

/** `GET /results/{token}/exceptions` */
export type ResultExceptions = {
  partial: boolean
  items: {
    date: string
    instrumentId?: string
    kind: string
    rule?: string
    action?: string
  }[]
}

/** `POST /results/{token}/retry` 202 */
export type ResultRetryAccepted = {
  publicToken: string
  status: ResultStatus
  resultSource: string
  retryOfToken: string
}

/** 공개 경로가 보내는 오류 이름. 화면은 문구가 아니라 이 이름으로 분기한다 */
export type ResultErrorCode =
  | 'RESULT_EXPIRED'
  | 'RESOURCE_NOT_FOUND'
  | 'INVALID_INPUT'
  | 'RESULT_NOT_READY'
  | 'BACKTEST_FAILED'
  | 'NO_PARTIAL_RESULT'
  | 'NOT_RETRYABLE'
  | 'EXECUTION_LIMIT'

export type ResultEnvelope<T> =
  | { success: true; data: T; error: null }
  | { success: false; data: null; error: { code: string; message: string } }
