import { fetchAnalysisVersions } from '@/api/analysisVersions'
import { createBacktest } from '@/api/backtest'
import { fetchEvaluationWindow } from '@/api/sectors'
import { judgeWarmupFromWindow } from '@/utils/evaluationWindow'
import { notConnectedSupport, type AnalysisSupport } from './analysisSupport'

/**
 * 실제 모듈로 채운 통로 한 벌(B06: T039·T042).
 *
 * <p><b>아직 라우트의 기본값이 아니다.</b> 004의 `GET /backtests/registry`가 dev에 없어, 이 통로로 바꾸면 버전
 * 조회가 없는 주소로 나가 실패한 뒤 '분석하기'가 막힌다. 004가 머지되면 `defaultAnalysisSupport`를 이것으로
 * 바꾼다(003 tasks.md T039·T107 메모).
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
 * 라우트가 쓰는 통로. **지금 dev에 있는 것만 실제로 잇는다**(T107).
 *
 * <ul>
 *   <li>공통 평가 기간 조회와 워밍업 판정: 001 `GET /sectors/evaluation-window`가 머지되어(MR !183) 실제 모듈을
 *       쓴다. 카드가 실제 자료로 워밍업과 최소 평가 기간을 판정한다.</li>
 *   <li>규칙·비용 버전 조회와 요청 전송: 004 `GET /backtests/registry`가 아직 없어 연결 전 상태로 둔다. 버전은
 *       `null`이라 '분석하기'가 "분석 조건 버전을 확인할 수 없습니다"로 막히고, 없는 주소로 요청을 보내지
 *       않는다.</li>
 * </ul>
 *
 * <p>004가 머지되면 이 상수를 `connectedAnalysisSupport`로 바꾼다. 화면은 이 이름만 알므로 다른 곳은 고치지
 * 않는다.
 */
export const defaultAnalysisSupport: AnalysisSupport = {
  ...notConnectedSupport,
  fetchEvaluationWindow: connectedAnalysisSupport.fetchEvaluationWindow,
  judgeWarmup: connectedAnalysisSupport.judgeWarmup,
}
