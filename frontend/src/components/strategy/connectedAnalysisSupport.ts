import { fetchAnalysisVersions } from '@/api/analysisVersions'
import { createBacktest } from '@/api/backtest'
import { fetchEvaluationWindow } from '@/api/sectors'
import { judgeWarmupFromWindow } from '@/utils/evaluationWindow'
import type { AnalysisSupport } from './analysisSupport'

/**
 * 실제 모듈로 채운 통로 한 벌(B06: T039·T042).
 *
 * <p><b>아직 라우트의 기본값이 아니다.</b> 2026-09-17 기준 001의 `GET /sectors/evaluation-window`(합의한
 * 필드 셋 포함)와 004의 규칙·비용 버전 조회가 모두 없다. 이 통로로 바꿔도 화면은 버전 `null`과 평가 기간
 * 조회 실패로 `notConnectedSupport`와 같게 막히지만, 존재하지 않는 주소로 요청을 보내게 된다. 두 선행이
 * 풀리면 `StrategySettingsPage`의 기본 `support`를 이것으로 바꾼다(003 tasks.md T039·T042 구현 메모).
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
