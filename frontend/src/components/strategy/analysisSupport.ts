import type { WarmupVerdict } from './StrategyCard'
import type { BacktestAccepted, BacktestCreateRequest } from '@/types/backtestRequest'
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
 *       없다는 사실을 `unknown`으로 넘기고 카드가 그대로 보인다(T084). T042가 `useEvaluationWindow`로
 *       채운다.</li>
 *   <li>요청 전송: `POST /backtests`. T039가 `api/backtest.ts`로 채운다.</li>
 * </ol>
 *
 * <p>화면이 이 셋을 props로 받는 까닭은, 없는 모듈을 import하지 않고도 요청 조립(SC-001)을 검사로
 * 고정하기 위해서다. <b>기본값은 정직하게 막힌 상태다</b> — 값을 지어내 채우는 경로를 두지 않는다
 * (원칙 IV·VI, FR-018).
 */

/** 분석 요청에 실을 규칙·비용 버전과, 비용 조건이 임시값인지(FR-002·FR-010). */
export type AnalysisVersions = {
  rulesVersion: string
  costProfileVersion: string
  costProfileProvisional: boolean
}

export type WarmupJudgeInput = {
  strategy: StrategyView
  /** 판정할 설정의 워밍업. 카드 목록은 기본 설정 기준, 선택 전략은 현재 설정 기준이다(FR-011) */
  warmupTradingDays: number
  /** 사용자가 고른 최소 평가 기간(연). `0`은 제한 없음이다(FR-011a) */
  minEvaluationYears: number
}

export type WarmupJudgement = {
  verdict: WarmupVerdict
  /**
   * 워밍업을 뺀 뒤 실제로 평가하는 기간. `ok`·`reduced`일 때만 있다.
   *
   * 분석 요청의 `conditions.start`·`end`가 이 값에서 온다. 사용자가 기간을 바꾸는 것은 B10이 붙인다.
   */
  window: { start: string; end: string } | null
}

export type AnalysisSupport = {
  fetchAnalysisVersions: (signal?: AbortSignal) => Promise<AnalysisVersions | null>
  judgeWarmup: (input: WarmupJudgeInput) => WarmupJudgement
  createBacktest: (request: BacktestCreateRequest) => Promise<BacktestAccepted>
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
  judgeWarmup: () => ({
    verdict: { state: 'unknown', message: EVALUATION_WINDOW_UNAVAILABLE },
    window: null,
  }),
  createBacktest: async () => {
    throw new Error('분석 요청 전송이 아직 연결되지 않았습니다.')
  },
}
