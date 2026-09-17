import { fetchAnalysisVersions } from '@/api/analysisVersions'
import { createBacktest } from '@/api/backtest'
import { fetchEvaluationWindow } from '@/api/sectors'
import { judgeWarmupFromWindow } from '@/utils/evaluationWindow'
import type { AnalysisSupport } from './analysisSupport'

/**
 * 실제 모듈로 채운 통로 한 벌(B06: T039·T042·T110).
 *
 * <p>001 `GET /sectors/evaluation-window`(MR !183)와 004 `GET /backtests/registry`(004 T025, `S15P21E205-326`)가
 * 모두 dev에 들어와 네 통로가 전부 실제 모듈이다.
 *
 * <p>모듈 상수라 렌더 사이에 참조가 같다(`AnalysisSupport`의 안정 참조 계약).
 */
export const connectedAnalysisSupport: AnalysisSupport = {
  fetchAnalysisVersions,
  fetchEvaluationWindow,
  judgeWarmup: ({ evaluationWindow, warmupTradingDays, minEvaluationYears }) =>
    judgeWarmupFromWindow(evaluationWindow, { warmupTradingDays, minEvaluationYears }),
  createBacktest,
}

/**
 * 라우트가 쓰는 통로(T107·T110).
 *
 * <p>한때 001만 dev에 있어 평가 기간 조회·판정만 실제 모듈로 잇고 버전 조회·전송은 막아 두었다. 004 registry가
 * 머지되어 이제 실제 모듈 한 벌 그대로다. 화면은 이 이름만 알므로, 다시 일부를 막아야 할 일이 생기면 이 상수만
 * 바꾼다.
 */
export const defaultAnalysisSupport: AnalysisSupport = connectedAnalysisSupport
