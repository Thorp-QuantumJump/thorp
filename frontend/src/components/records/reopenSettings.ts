import {
  type PrefillOutcome,
  prefillStrategySettings,
} from '@/components/result/prefillStrategySettings'
import type { MyRecordDetail } from '@/types/myRecords'

export const REOPEN_UNAVAILABLE = '이 기록은 설정 바꿔 비교로 열 수 없습니다.'

/**
 * 내 기록의 '설정 바꿔 비교'(spec 006 FR-007, research R4, contracts/record-handoff.md).
 *
 * <p><b>006은 인계 코드를 만들지 않는다.</b> 기록 상세의 `reopenPayload`는 005 결과의 `config`와 같은 모양이라, 005가
 * 006용으로 열어 둔 `prefillStrategySettings`에 <b>그대로</b> 넘긴다. 값을 자르거나 채우지 않고(`warmupTradingDays`도
 * 그대로), 대응표가 달라 섹터를 넘길 수 없는 판정도 그 함수가 한다. 두 벌이면 "결과에서 열면 되는데 기록에서 열면 안 되는"
 * 모습으로 어긋난다.
 *
 * <p>서버가 열 수 없다고 판정한 기록(`reopenSettings.available === false`, 경제환경·별도 시나리오)이나 사본이 없는 기록은
 * 부르지 않고 `failed`로 돌려준다 — 화면은 그런 기록에 버튼을 두지 않으므로 정상 경로에서는 오지 않는다.
 */
export async function reopenSettings(
  detail: MyRecordDetail,
  navigate: (path: string) => void,
  signal?: AbortSignal,
): Promise<PrefillOutcome> {
  if (detail.reopenSettings?.available !== true || !detail.reopenPayload) {
    return { status: 'failed', message: REOPEN_UNAVAILABLE }
  }
  return prefillStrategySettings(detail.reopenPayload, navigate, signal)
}
