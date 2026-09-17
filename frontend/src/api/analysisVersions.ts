import type { AnalysisVersions } from '@/components/strategy/analysisSupport'

/**
 * 분석 요청에 실을 규칙·비용 버전을 읽는 **유일한 자리**다(003 T039, "착수 전에 알아둘 것" ②).
 *
 * <p><b>아직 읽을 곳이 없어 `null`을 돌려준다.</b> 2026-09-17 기준 004에는 규칙 버전을 조회하는 HTTP
 * 표면이 없다(`StrategyRulesService.findAll()`은 있으나 엔드포인트가 없다). 비용 조건도 기본 버전
 * 이름(`PROVISIONAL-DEMO-1`)과 수치·임시 여부를 화면이 읽을 수단이 없다(data-model §7 "확인된 빈 구멍").
 * 화면은 `null`을 받으면 '분석하기'를 "분석 조건 버전을 확인할 수 없습니다"로 막는다.
 *
 * <p><b>값을 지어내지 않는다.</b> 규칙 버전을 상수로 적어 보내면 004가 등록표를 바꿨을 때 옛 버전으로
 * 조용히 접수되거나 422로 거절되고, 비용 수치를 적어 두면 FR-010이 금지한 "확정값처럼 보이는 임시값"이
 * 된다(원칙 IV·VI).
 *
 * <p>004와 조회 표면이 정해지면 이 파일만 바꾼다. 검토 중인 갈래는 셋이다: 004가 조회 API를 연다 / 003
 * 등록표 응답에 전략별 `rulesVersion`을 싣는다 / `rulesVersion`을 선택 필드로 바꾸고 서버가 채운다.
 */
export async function fetchAnalysisVersions(signal?: AbortSignal): Promise<AnalysisVersions | null> {
  void signal
  return null
}
