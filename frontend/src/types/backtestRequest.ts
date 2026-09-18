import type { AxisValue } from './strategyRegistry'

/**
 * `POST /backtests`(schema v2, spec 004)의 요청·202 응답 타입.
 *
 * 원본 계약은 `docs/specs/backtest-api/README.md` §1이며, 그 원본은
 * `specs/004-backtest-execution/contracts/rest-api.md`다. 003의 분석 요청과 005의 비교 요청이 공유한다.
 * `evaluationSplitVersion`·`scenarioParams`는 005가 원본 결과의 평가 조건을 보존할 때 사용한다.
 *
 * `types/backtest.ts`는 v1 데모 화면(`BacktestPage`)이 쓰는 이전 모양이라 여기와 다르다.
 *
 * **금액과 비율은 문자열이다.** 계약이 부동소수 오차를 막기 위해 `initialCash`·`targetWeight`를
 * decimal 문자열로 받는다. 숫자를 그대로 실으면 400이다.
 */

export type BacktestExecutionType = 'SINGLE' | 'SEARCH_BUNDLE' | 'REGIME_SECTOR' | 'REGIME_TOTAL'

export type BacktestScenario = 'FULL_PERIOD' | 'FRESH_CASH_FROM' | 'COST_ESCALATION' | 'TARGET_RISK'

export type SectorAllocationItem = {
  sectorId: string
  /** `{6자리 종목코드}.XKRX` */
  instrumentId: string
  /**
   * decimal 문자열(예: `"0.4000"`). **목표 비중을 사용자가 정하는 전략(`targetWeightSource: USER`)
   * 에서만 싣고 다른 전략에서는 싣지 않는다** — 다른 전략에 실으면 400이다.
   */
  targetWeight?: string
}

export type BacktestCreateRequest = {
  executionType: BacktestExecutionType
  sectorAllocation: {
    /** 002가 값을 만들 당시의 섹터-ETF 대응표 버전 */
    mappingVersion: string
    items: SectorAllocationItem[]
  }
  strategy: {
    strategyId: string
    /** 전략 규칙 버전. 필수이며 출처는 004와 함께 정한다("착수 전에 알아둘 것" ②) */
    rulesVersion: string
    /** 003 등록표 버전. 실린 요청만 접수 시점에 등록표로 재검증된다(FR-006) */
    registryVersion?: string
    /** 축 이름 → 값. 등록표의 허용 범위 안이어야 한다 */
    parameters: Record<string, AxisValue>
  }
  conditions: {
    start: string
    end: string
    /** decimal 문자열(예: `"10000000.00"`) */
    initialCash: string
    /** `null`이면 서버 기본 비용 프로필 */
    costProfileVersion: string | null
    /** 워밍업 규칙의 계산 결과. 선택 전략의 요구값보다 작으면 422 */
    warmupTradingDays: number
    scenario: BacktestScenario
    evaluationSplitVersion?: string | null
    scenarioParams?: Record<string, unknown>
  }
}

export type BacktestRequestStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

/** 202 응답의 `data`에서 화면이 읽는 부분. `backtestId`는 길이를 가정하지 않고 문자열로 다룬다. */
export type BacktestAccepted = {
  backtestId: string
  /** 공개 결과 화면은 이 토큰만 보관한다. 이전 접수 응답에는 없을 수 있다. */
  publicToken?: string
  status: BacktestRequestStatus
  /** `REUSED`면 이미 완료된 결과라 폴링을 시작하지 않는다 */
  resultSource?: 'NEW' | 'JOINED' | 'REUSED'
}
