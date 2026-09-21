/**
 * spec 007 설정 조합 자동 비교(자동 파라미터 탐색)의 HTTP 계약 타입.
 *
 * 원본 계약은 `docs/specs/param-search-api/README.md`이며 이 파일의 필드는 그것과 1:1이다.
 * 세 가지를 계약에서 그대로 옮겼다.
 *
 * - **금액·비율은 문자열이다.** 부동소수 오차를 피하려고 백엔드가 문자열로 내보낸다. 화면에서
 *   숫자로 바꿔야 할 때만 바꾸고, 비교·정렬을 위해 미리 `Number()`로 접지 않는다.
 * - **계산하지 않은 값은 `null`이며 0이 아니다.** 제외·실패·미실행 후보의 성과는 `null`이고
 *   화면은 그것을 "0%"가 아니라 사유와 함께 보인다(FR-011, 원칙 VI).
 * - **전략 데이터는 응답에만 있다.** 전략 목록·이름·축 라벨·단위·기본값·후보값·허용 범위를
 *   이 파일에 상수로 두지 않는다(FR-025). 타입은 모양만 정하고 값은 서버가 정한다.
 */

/** 탐색 묶음의 상태. `REJECTED`는 접수 시점 거부이며 후보 행이 없다. */
export type ParamSearchStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'REJECTED'

/** 묶음이 멈춘 사유. 멈추지 않았으면 `null`이다. */
export type ParamSearchStopReason =
  | 'CANCELLED'
  | 'CANDIDATE_LIMIT'
  | 'TIME_LIMIT'
  | 'FAILURE_LIMIT'
  | 'NO_VALID_CANDIDATE'
  | 'STRATEGY_NOT_REGISTERED'
  | 'STRATEGY_NOT_EXECUTABLE'

/** 후보 하나의 상태. */
export type CandidateStatus =
  | 'PENDING'
  | 'DISPATCHED'
  | 'COMPLETED'
  | 'FAILED'
  | 'EXCLUDED'
  | 'SKIPPED'

/**
 * 검증 라벨. `UNVALIDATED`는 "통과"가 아니라 **"검증 여부를 말할 수 없다"**는 뜻이다(FR-015, 원칙 VII).
 * 평가 분할(PD-12)이 등록되기 전에는 모든 결과가 이 값이다.
 */
export type ValidationLabel = 'SEARCH_RESULT' | 'HELD_OUT_RESULT' | 'UNVALIDATED'

/** 선정 결과에서 후보가 어떤 처지였는지. 선정 계산 전에는 `selectionReason` 자체가 `null`이다. */
export type SelectionOutcome =
  | 'SELECTED'
  | 'NOT_IN_DISPLAY'
  | 'FILTERED_OUT'
  | 'EXCLUDED_BEFORE_RUN'
  | 'NOT_EVALUATED'

/** 선정에 쓰는 필터 이름. */
export type SelectionFilter = 'DRAWDOWN' | 'STABILITY'

/**
 * 후보 목록을 정렬할 수 있는 항목. **서버가 허용하는 것이 전부이고 그 밖의 값은 400이다.**
 *
 * 한 항목 기준 정렬은 FR-017이 허용한다 — 정렬은 보기 편하라고 있는 것이고 순위를 뜻하지 않는다.
 */
export type CandidateSort =
  | 'candidateNo'
  | 'netReturn'
  | 'maxDrawdown'
  | 'tradingCost'
  | 'stability'

/** 축 후보값과 파라미터 값. 등록표가 정하는 값이라 숫자·문자열 어느 쪽도 올 수 있다. */
/**
 * 축의 후보값 하나.
 *
 * **객체도 온다.** 리밸런싱 주기처럼 값 자체가 구조를 갖는 축이 있어(`{mode, unit}`) 숫자·문자열로만
 * 좁혀 두면 타입이 실제 응답과 어긋나고, 화면은 그 값을 `String()`으로 찍어 `[object Object]`를
 * 보이게 된다. **값은 요청에 되돌려 보낼 때만 쓰고, 그릴 때는 `SpaceAxis.valueLabels`를 쓴다.**
 */
export type AxisValue = number | string | Record<string, unknown>

/** 007이 쓰는 응답 껍데기. 오류 코드가 auth와 달라 `types/auth.ts`의 것을 재사용하지 않는다. */
export type ParamSearchEnvelope<T> =
  | { success: true; data: T; error: null }
  | { success: false; data: null; error: { code: string; message: string } }

// ---------------------------------------------------------------------------
// 요청
// ---------------------------------------------------------------------------

/**
 * 미리보기·실행 요청 본문. 두 엔드포인트가 같은 모양을 쓰고 `registryVersion`만 다르다
 * (미리보기는 무시, 실행은 필수).
 *
 * `space`는 **전략별로 중첩**한다 — 축 이름이 전략마다 겹칠 수 있기 때문이다. 생략하면 등록표
 * 전체 범위를 쓴다. 값은 등록표의 후보값이어야 하며 `allowedRange` 안이라도 등록되지 않은 값은
 * `400 UNREGISTERED_PARAMETER_VALUE`다.
 */
export type ParamSearchRequest = {
  universe: { instrumentIds: string[] }
  strategy: { strategyIds: string[] }
  run?: { start?: string; end?: string }
  portfolio?: { initialCash?: string }
  space?: SearchSpaceSelection
  registryVersion?: string
}

/**
 * 사용자가 좁힌 탐색 공간. `전략 ID → 축 이름 → 고른 후보값`이다.
 *
 * **비어 있는 것과 전부 고른 것은 같은 뜻이다** — 둘 다 등록표 전체 범위를 쓴다. 그래서 화면은
 * 아무것도 좁히지 않았으면 `space`를 아예 보내지 않는다.
 */
export type SearchSpaceSelection = Record<string, Record<string, AxisValue[]>>

// ---------------------------------------------------------------------------
// 미리보기 응답 (POST /param-searches/preview)
// ---------------------------------------------------------------------------

/** 등록표가 아는 전략. `executable`은 004가 실행을 지원하는지다. */
export type PreviewStrategy = {
  strategyId: string
  name: string
  executable: boolean
}

/** 축의 허용 범위. **화면 표시용이며 입력 허용 범위가 아니다** — 입력은 `values`만 받는다. */
export type AxisAllowedRange = {
  min: number | null
  max: number | null
  step: number | null
  values: AxisValue[] | null
}

/**
 * 탐색 축 하나. `values` 순서가 인접 판정의 근거이고 `axes` 순서가 그리드 좌표 축 순서다(FR-029).
 *
 * `defaultValue`는 등록된 **기본 시작값**이며 추천값이나 최적값이 아니다 — 화면에 '추천'·'최적'
 * 문구를 붙이지 않는다(원칙 III).
 */
export type SpaceAxis = {
  axis: string
  label: string
  unit: string | null
  values: AxisValue[]
  /** `values`와 같은 길이·같은 순서의 표시 문구. 등록표가 소유하며 화면이 짓지 않는다(FR-025). */
  valueLabels: string[]
  defaultValue: AxisValue | null
  allowedRange: AxisAllowedRange | null
}

/** 전략 하나의 탐색 공간 요약. 축이 없으면 조합 1건이다(FR-027). */
export type StrategySpaceSummary = {
  strategyId: string
  strategyName: string
  axes: SpaceAxis[]
  combinations: number
}

/** 유효성 위반으로 계산 전에 빠진 조합의 사유. */
export type ExclusionReason = {
  code: string
  message: string
  count: number
}

/** 예상 작업량. `excludedCombinations`는 계산 전에 빠지는 조합 수다(FR-006). */
export type PreviewWorkload = {
  totalCombinations: number
  validCombinations: number
  excludedCombinations: number
  exclusionReasons: ExclusionReason[]
}

/**
 * 묶음에 걸린 한도. `provisional`에 든 항목은 **실측 전 임시값**이라 화면이 그 사실을 표시한다
 * (FR-005a, 원칙 IX).
 */
export type AppliedLimits = {
  maxCandidates: number
  timeLimitSeconds: number
  dispatchWindow: number
  consecutiveFailureLimit: number
  provisional: string[]
}

/**
 * 미리보기가 주는 한도. `exceeded`가 `true`면 실행은 거부되고 화면은 범위를 좁히라고 안내한다.
 *
 * **이 필드는 미리보기에만 있다** — 상태 응답의 `config.limits`는 이미 접수된 묶음에 걸린 값이라
 * "넘었는가"라는 질문 자체가 없다. 두 타입을 하나로 합치면 없는 필드를 읽게 된다.
 */
export type ParamSearchLimits = AppliedLimits & {
  exceeded: boolean
}

/** 종목들이 겹치는 평가 구간. `shortenedBy`가 있으면 그 축 때문에 구간이 짧아졌다. */
export type CommonPeriod = {
  start: string
  end: string
  shortenedBy: string | null
}

/** 확인 화면의 네 항목(대상 전략·탐색 범위 요약·예상 작업량·적용 한도)이 여기 다 있다. */
export type ParamSearchPreview = {
  registryVersion: string
  strategies: PreviewStrategy[]
  spaceSummary: StrategySpaceSummary[]
  workload: PreviewWorkload
  limits: ParamSearchLimits
  commonPeriod: CommonPeriod | null
  validationLabel: ValidationLabel
  validationNote: string | null
}

// ---------------------------------------------------------------------------
// 접수·상태 응답 (POST /param-searches, GET /param-searches/{searchId})
// ---------------------------------------------------------------------------

/** 후보 집계. 합이 맞지 않아도 화면이 만들어 채우지 않는다. */
export type SearchCounts = {
  expected: number
  valid: number
  completed: number
  failed: number
  excluded: number
  pending: number
  skipped: number
}

/** 상태 응답이 되돌려 주는 탐색 공간. 미리보기의 축과 달리 라벨·단위·후보값만 싣는다. */
export type ConfigSpaceAxis = {
  name: string
  label: string
  unit: string | null
  values: AxisValue[]
}

export type ConfigStrategySpace = {
  strategyName: string
  axes: ConfigSpaceAxis[]
}

/** 손실 제약. `type`이 `NOT_APPLIED`이거나 `baseline`이 없으면 걸지 않은 것이다. */
export type DrawdownFilterRule = {
  type: string
  baseline: string | null
  baselineName?: string | null
}

export type StabilityFilterRule = {
  type: string
  keepTopPercent: number
}

/**
 * 선정 규칙 스냅샷. **묶음이 시작될 때 굳고 이후 바뀌지 않는다**(FR-013c) — 나중에 규칙이 바뀌어도
 * 끝난 묶음은 그때의 규칙으로 읽어야 재현이 성립한다.
 */
export type SelectionRule = {
  drawdownFilter: DrawdownFilterRule | null
  stabilityFilter: StabilityFilterRule | null
  sortMetric: string
  displayCount: number
  tieHandling: string
  stabilityFormula?: { neighbor: string; value: string } | null
  provisional?: string[]
}

/** 재현에 필요한 참조 버전들. */
export type ReferenceVersions = {
  marketData?: string
  registry?: string
  cost?: string
  engine?: string
}

export type ParamSearchConfig = {
  strategyIds: string[]
  instrumentIds: string[]
  registryVersion: string
  searchMethod: string
  randomSeed: number | null
  space: Record<string, ConfigStrategySpace>
  selectionRule: SelectionRule | null
  limits: AppliedLimits | null
  referenceVersions: ReferenceVersions | null
  dataVersionChanged: boolean
}

/**
 * 손실 제약의 기준이 된 실행. 기준 실행을 만들지 않았으면 전체가 `null`이고, 만들었지만 아직
 * 끝나지 않았으면 성과만 `null`이다.
 */
export type ParamSearchBaseline = {
  strategyId: string
  strategyName: string | null
  backtestId: string | null
  status: string | null
  netReturn: string | null
  maxDrawdown: string | null
}

/**
 * 선정 결과. `status`가 `COMPLETED`·`PARTIAL`일 때만 채워진다.
 *
 * **`displayed`는 순위가 아니다.** 정렬 지표를 밝힌 집합이며 화면에 순위 번호를 붙이지 않는다
 * (원칙 III). `shortOfDisplayCount`가 `true`여도 문턱을 낮춰 채우지 않는다(원칙 VII).
 */
export type ParamSearchSelection = {
  displayed: string[]
  shortOfDisplayCount: boolean
  note: string | null
  drawdownFilterApplied: boolean
}

/** 접수 응답(202)과 상태 조회 응답(200)이 같은 모양을 쓴다. 접수 직후엔 대부분 `null`이다. */
export type ParamSearchDetail = {
  searchId: string
  status: ParamSearchStatus
  stopReason: ParamSearchStopReason | null
  counts: SearchCounts | null
  partial: boolean
  partialNote: string | null
  config: ParamSearchConfig | null
  baseline: ParamSearchBaseline | null
  validationLabel: ValidationLabel
  validationNote: string | null
  selection: ParamSearchSelection | null
  startedAt: string | null
  finishedAt: string | null
}

// ---------------------------------------------------------------------------
// 후보 목록 (GET /param-searches/{searchId}/candidates)
// ---------------------------------------------------------------------------

/** 이웃 후보와의 성과 차이. 이웃이 없으면 `calculable: false`에 `value: null`이다. */
export type CandidateStability = {
  calculable: boolean
  value: string | null
  neighborCount: number
}

/**
 * 후보가 표시·탈락·미평가 중 어디에 속하는지와 그 근거.
 *
 * `FILTERED_OUT`은 **탈락**이고 `NOT_IN_DISPLAY`는 **"떨어진 것이 아니라 표시하지 않은 것"**이다 —
 * 화면에서 두 문구를 구분한다.
 */
export type CandidateSelectionReason = {
  outcome: SelectionOutcome
  tied?: boolean
  passedFilters?: SelectionFilter[]
  failedFilter?: SelectionFilter
  code?: string
  message?: string
}

export type SearchCandidateItem = {
  candidateId: string
  candidateNo: number
  strategyId: string
  parameters: Record<string, AxisValue>
  status: CandidateStatus
  backtestId: string | null
  netReturn: string | null
  maxDrawdown: string | null
  tradingCost: string | null
  stability: CandidateStability | null
  retryCount: number
  validationLabel: ValidationLabel
  selected: boolean
  selectionReason: CandidateSelectionReason | null
}

export type CandidatePage = {
  page: number
  size: number
  totalElements: number
  totalPages: number
  content: SearchCandidateItem[]
}

// ---------------------------------------------------------------------------
// 취소·재시도 (POST /param-searches/{searchId}/cancel, /retry-failed)
// ---------------------------------------------------------------------------

/** 취소 응답. `inFlight`는 이미 큐에 들어가 끝까지 도는 후보 수다 — 그것이 끝나야 `PARTIAL`이 된다. */
export type ParamSearchCancelResult = {
  searchId: string
  status: ParamSearchStatus
  skipped: number
  inFlight: number
}

/** 재시도 응답. 발행 창을 넘겨 보내지 않으므로 `remainingFailed`가 남으면 다시 요청한다. */
export type ParamSearchRetryResult = {
  searchId: string
  status: ParamSearchStatus
  retried: number
  remainingFailed: number
}
