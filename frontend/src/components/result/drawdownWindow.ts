import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * 최대 하락 구간(FR-002). 그래프에 음영으로 겹쳐 그릴 고점~저점 두 날짜다.
 *
 * <p><b>004가 `summary.maxDrawdownWindow`를 주면 그 값을 쓰고 이 함수를 지운다</b>
 * (spec Assumptions가 허용한 유일한 계산 예외, R-05). 그때까지는 받은 시계열에서 구간을 찾되,
 * 찾은 저점이 `summary.maxDrawdown`과 다른 말을 하면 음영을 포기한다 — 숫자와 그림이 어긋나 보이는
 * 것보다 음영이 없는 편이 낫다(원칙 VI).
 */

/** 음영을 칠할 구간. 시작은 운용 중 가장 높았던 날, 끝은 그 뒤 가장 낮았던 날이다 */
export type DrawdownWindow = {
  peakDate: string
  troughDate: string
}

/**
 * 저점의 하락 폭과 `summary.maxDrawdown`이 같다고 볼 수 있는 차이의 한계.
 *
 * 계약이 비율을 소수점 여섯 자리 문자열로 주므로, 두 값이 같은 계산에서 나왔어도 마지막 자리에서
 * 갈릴 수 있다. 이보다 큰 차이는 두 값이 다른 계산에서 나왔다는 뜻으로 읽는다.
 */
const MATCH_TOLERANCE = 1e-6

/** 수로 읽히면 그 수, 아니면 `null`. 읽을 수 없는 값을 0으로 바꾸지 않는다 */
function toNumber(value: string | undefined | null): number | null {
  if (value == null || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 시계열에서 최대 하락 구간을 찾는다.
 *
 * <p>저점은 `drawdown`이 가장 낮은 날이며, 같은 값이 여러 날이면 처음 날을 쓴다 — 하락이 처음
 * 바닥에 닿은 날이 구간의 끝이다. 고점은 저점까지의 `equity`가 가장 높았던 날이고, 같은 값이 여러
 * 날이면 마지막 날을 쓴다 — 고점에 머무른 날들은 아직 하락이 아니므로 구간에 넣지 않는다.
 *
 * <p>회복 시점은 구간에 넣지 않는다(FR-002). 회복까지 걸린 기간은 상세의 숫자로 본다.
 *
 * @param points 받은 시계열. 점을 줄이지 않은 원본이어야 저점이 남는다(R-08)
 * @param summaryMaxDrawdown `summary.maxDrawdown`. 없거나 저점과 어긋나면 `null`을 돌려준다
 */
export function findDrawdownWindow(
  points: ResultTimeseriesPoint[],
  summaryMaxDrawdown: string | null | undefined,
): DrawdownWindow | null {
  const reported = toNumber(summaryMaxDrawdown)
  // 하락이 없었다고 보고된 결과에는 칠할 구간이 없다.
  if (reported == null || reported >= 0) return null

  let troughIndex = -1
  let troughDrawdown = Number.POSITIVE_INFINITY
  for (let index = 0; index < points.length; index += 1) {
    const drawdown = toNumber(points[index].drawdown)
    if (drawdown == null) continue
    if (drawdown < troughDrawdown) {
      troughDrawdown = drawdown
      troughIndex = index
    }
  }
  if (troughIndex < 0 || troughDrawdown >= 0) return null
  if (Math.abs(troughDrawdown - reported) > MATCH_TOLERANCE) return null

  let peakIndex = -1
  let peakEquity = Number.NEGATIVE_INFINITY
  for (let index = 0; index <= troughIndex; index += 1) {
    const equity = toNumber(points[index].equity)
    if (equity == null) continue
    if (equity >= peakEquity) {
      peakEquity = equity
      peakIndex = index
    }
  }
  // 고점을 찾지 못했거나 고점과 저점이 같은 날이면 그릴 구간이 없다.
  if (peakIndex < 0 || peakIndex >= troughIndex) return null

  return {
    peakDate: points[peakIndex].date,
    troughDate: points[troughIndex].date,
  }
}
