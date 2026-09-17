import type { WarmupVerdict } from './StrategyCard'
import type { BacktestAccepted, BacktestCreateRequest } from '@/types/backtestRequest'
import type { EvaluationWindow, EvaluationWindowQuery } from '@/types/evaluationWindow'
import type { StrategyView } from '@/types/strategyRegistry'

/**
 * 전략 화면이 분석을 시작하기 위해 바깥에서 받아야 하는 세 통로의 자리다.
 *
 * <p><b>B05는 자리만 만들고 B06(T039·T042)이 채운다.</b> 셋 다 아직 화면이 알 길이 없는 값이다.
 *
 * <ol>
 *   <li>규칙·비용 버전(②): 004의 `rulesVersion`(필수)·`costProfileVersion`을 화면이 어디서 읽을지
 *       미결이다. 결정 전에는 `null`이고 화면은 '분석하기'를 "분석 조건 버전을 확인할 수 없습니다"로
 *       막는다. T039가 `api/analysisVersions.ts` 한 파일로 격리해 채운다.</li>
 *   <li>워밍업 판정(①): 001의 `GET /sectors/evaluation-window`가 아직 없다. 그 전까지는 판정할 수
 *       없다는 사실을 `unknown`으로 넘기고 카드가 그대로 보인다(T084). T042가 조회
 *       (`fetchEvaluationWindow`)와 판정(`judgeWarmupFromWindow`)으로 나눠 채운다. 화면은 섹터 집합이
 *       바뀔 때만 조회하고(`useEvaluationWindow`), 받은 상태를 판정 입력으로 넘긴다(T098).</li>
 *   <li>요청 전송: `POST /backtests`. T039가 `api/backtest.ts`로 채운다.</li>
 * </ol>
 *
 * <p>화면이 이 셋을 props로 받는 까닭은, 없는 모듈을 import하지 않고도 요청 조립(SC-001)을 검사로
 * 고정하기 위해서다. <b>기본값은 정직하게 막힌 상태다</b> — 값을 지어내 채우는 경로를 두지 않는다
 * (원칙 IV·VI, FR-018).
 */

/**
 * 004가 등록한 비용 조건의 수치(bps). 화면은 읽기 전용으로만 보인다(FR-010).
 *
 * 004 `CostProfile`의 네 칸과 같다. 화면이 수치를 짓거나 고치는 경로를 두지 않는다.
 */
export type CostRates = {
  commissionBps: number
  spreadBps: number
  slippageBps: number
  taxBps: number
}

/**
 * 분석 요청에 실을 규칙·비용 버전과, 그 비용 조건의 수치·임시 여부(FR-002·FR-010).
 *
 * <p><b>버전과 수치는 한 덩이로 온다.</b> 버전만 알고 수치를 모르는 상태를 두면 화면이 "적용됨"만
 * 보이게 되고, 그것은 FR-010을 충족하지 않는다(data-model §7). 채우는 통로는 B06이 정한다.
 */
export type AnalysisVersions = {
  rulesVersion: string
  costProfileVersion: string
  costProfileProvisional: boolean
  costRates: CostRates
}

/**
 * 화면이 받아 둔 공통 평가 기간의 상태. 판정은 이것만으로 한다 — 값을 바꾸는 동안 서버에 묻지 않는다
 * (research R7-1, T098).
 */
export type EvaluationWindowState =
  /** 조회 중이거나 아직 조회할 조건(섹터·등록표)이 갖춰지지 않았다 */
  | { status: 'loading' }
  /** 등록표에서 워밍업 상한을 계산하지 못해 조회하지 않았다 */
  | { status: 'uncomputable' }
  /** 조회가 실패했거나 응답이 계약과 어긋났다 */
  | { status: 'unavailable' }
  | { status: 'loaded'; window: EvaluationWindow }

export type WarmupJudgeInput = {
  strategy: StrategyView
  /** 받아 둔 공통 평가 기간. 카드마다 같은 값이다 */
  evaluationWindow: EvaluationWindowState
  /** 판정할 설정의 워밍업. 카드 목록은 기본 설정 기준, 선택 전략은 현재 설정 기준이다(FR-011) */
  warmupTradingDays: number
  /** 사용자가 고른 최소 평가 기간(연). `0`은 제한 없음이다(FR-011a) */
  minEvaluationYears: number
}

/**
 * 워밍업 때문에 공통 평가 구간의 시작이 늦춰졌다는 사실과 그 원인(FR-011, SC-004).
 *
 * 001 `GET /sectors/evaluation-window`가 돌려주는 값에서 옮긴다. 화면이 날짜를 계산하지 않는다.
 */
export type PeriodReduction = {
  /** 워밍업 없이 계산했을 때의 공통 시작일 */
  startWithoutWarmup: string
  /** 시작일을 정한 후보의 `instrumentId`(001 `bindingCandidate`) */
  bindingCandidate: string
}

export type WarmupJudgement = {
  verdict: WarmupVerdict
  /**
   * 워밍업을 채운 뒤 평가할 수 있는 공통 범위(001의 `commonStart`~`commonEnd`). `ok`·`reduced`일 때만 있다.
   *
   * <p>사용자가 고르는 분석 기간은 **이 범위 안에서만** 움직인다(FR-009). 사용자가 기간을 고르지 않았으면
   * 이 범위 전체가 분석 요청의 `conditions.start`·`end`가 된다.
   */
  window: { start: string; end: string } | null
  /** 워밍업 때문에 시작일이 늦춰졌으면 그 사실. 늦춰지지 않았거나 판정하지 못했으면 `null` */
  reduction: PeriodReduction | null
}

/**
 * 화면에 넘기는 통로 한 벌.
 *
 * <p><b>각 함수를 렌더 사이에 안정된 참조로 넘긴다.</b> 모듈 함수이거나 `useCallback`·`useMemo`로
 * 감싼 값이어야 한다.
 *
 * <p>화면은 버전 조회를 **객체가 아니라 `fetchAnalysisVersions` 함수의 참조**에 맞춰 다시 한다. 그래서
 * 객체를 매번 `{ ...base }`로 새로 펼쳐도 담긴 함수가 같으면 요청은 한 번이고, 통로를 실제로 다른
 * 함수로 바꾸면 버전 조회도 새 통로를 따른다. 다시 조회하는 동안에는 받은 버전을 거두지 않아
 * '분석하기'가 깜빡이지 않는다.
 *
 * <p><b>이 계약을 어기면 생기는 일</b>: 조회 함수 자체를 렌더마다 새로 만들면 부모가 다시 그릴 때마다
 * 요청이 한 번씩 더 나간다. 화면은 깜빡이지 않으므로 사용자에게는 드러나지 않고, 헛도는 요청만
 * 남는다. 한때 화면이 열릴 때 한 번만 조회해 이 비용을 없앴으나, 그러면 통로를 바꿔도 버전 조회만
 * 옛 통로에 멈춰 '분석하기'가 계속 막혔다(S15P21E205-274).
 */
export type AnalysisSupport = {
  fetchAnalysisVersions: (signal?: AbortSignal) => Promise<AnalysisVersions | null>
  /**
   * 섹터 집합의 공통 평가 기간을 받는다. 실패하면 거부한다. 화면은 섹터 집합이나 상한이 바뀔 때만
   * 부른다.
   */
  fetchEvaluationWindow: (query: EvaluationWindowQuery, signal?: AbortSignal) => Promise<EvaluationWindow>
  judgeWarmup: (input: WarmupJudgeInput) => WarmupJudgement
  /**
   * 요청을 보낸다. 거절되면 **서버가 보낸 오류 이름을 `code`에, 응답의 `details`를 `details`에 실은
   * 오류로** 거부하고, 메시지는 서버가 보낸 문장을 쓴다.
   *
   * 화면은 `code`로 거절마다 계약 §2의 처리를 가르고(`readBacktestRejection`), 400이면 `details.axis`로
   * 항목 이름을 찾는다. 공용 `ApiClientError`는 `code`는 싣지만 `details`는 싣지 않는다. Axios 오류를
   * 그대로 던지면 그 `code`는 `ERR_BAD_REQUEST` 같은 전송 계층 이름이라 가를 수 없으니, B06(T039)의
   * `BacktestRequestError`가 둘을 정규화한 뒤 던진다.
   */
  createBacktest: (request: BacktestCreateRequest) => Promise<BacktestAccepted>
}

/**
 * 분석 요청의 거절 가운데 화면이 따로 처리하는 것(계약 §2 거절 응답).
 *
 * 422 `VALIDATION_RULE_VIOLATED`는 여기 없다. 서버가 규칙의 `detail`을 메시지로 싣고, 화면은 그
 * 메시지를 그대로 보이면 계약을 만족하기 때문이다.
 */
export const BacktestRejectionCode = {
  /** 400. 축 값이나 시작 금액이 허용 범위 밖이다 */
  UNREGISTERED_PARAMETER_VALUE: 'UNREGISTERED_PARAMETER_VALUE',
  /** 409. 요청을 보낸 사이에 등록표 버전이 바뀌었다 */
  REGISTRY_VERSION_CHANGED: 'REGISTRY_VERSION_CHANGED',
  /** 422. 고른 전략이 등록표의 활성 전략에 없다 */
  STRATEGY_NOT_REGISTERED: 'STRATEGY_NOT_REGISTERED',
  /** 503. 서버가 등록표를 읽지 못했다 */
  REGISTRY_UNAVAILABLE: 'REGISTRY_UNAVAILABLE',
} as const

/** `createBacktest`가 던진 오류에서 화면이 읽는 두 가지. 모르는 모양이면 둘 다 `null`이다. */
export type BacktestRejection = {
  /** 서버가 보낸 오류 이름 그대로. 아는 이름이 아니어도 접지 않는다 */
  code: string | null
  /** 거절 응답의 `details`. 400이면 `axis`·`value`·`allowedRange`를 싣는다 */
  details: Record<string, unknown> | null
}

/**
 * `createBacktest`가 던진 오류를 읽는다.
 *
 * 오류 클래스를 묻지 않고 `code`와 `details`만 본다. 전송 통로는 B06이 채우므로, 이 화면이 특정 클래스를
 * 알면 통로를 바꿀 때 판별이 조용히 빗나간다.
 */
export function readBacktestRejection(error: unknown): BacktestRejection {
  if (typeof error !== 'object' || error === null) return { code: null, details: null }
  const { code, details } = error as { code?: unknown; details?: unknown }
  return {
    code: typeof code === 'string' ? code : null,
    details:
      typeof details === 'object' && details !== null && !Array.isArray(details)
        ? (details as Record<string, unknown>)
        : null,
  }
}

/** `createBacktest`가 던진 오류가 등록표 버전 변경(409)인가. */
export function isRegistryVersionChanged(error: unknown): boolean {
  return readBacktestRejection(error).code === BacktestRejectionCode.REGISTRY_VERSION_CHANGED
}

export const ANALYSIS_VERSIONS_UNAVAILABLE = '분석 조건 버전을 확인할 수 없습니다.'
export const EVALUATION_WINDOW_UNAVAILABLE =
  '공통 평가 기간을 확인할 수 없어 분석을 시작할 수 없습니다.'

/**
 * 아직 아무것도 연결되지 않은 상태. 라우트가 화면을 그릴 때 쓰는 기본값이다.
 *
 * '분석하기'는 버전을 알 수 없어 막히고, 카드는 판정할 수 없다는 사실을 보인다. 전송은 그 앞에서
 * 막히므로 불릴 일이 없지만, 불리면 값을 지어내 보내는 대신 실패한다.
 */
export const notConnectedSupport: AnalysisSupport = {
  fetchAnalysisVersions: async () => null,
  fetchEvaluationWindow: async () => {
    throw new Error('공통 평가 기간 조회가 아직 연결되지 않았습니다.')
  },
  judgeWarmup: () => ({
    verdict: { state: 'unknown', message: EVALUATION_WINDOW_UNAVAILABLE },
    window: null,
    reduction: null,
  }),
  createBacktest: async () => {
    throw new Error('분석 요청 전송이 아직 연결되지 않았습니다.')
  },
}
