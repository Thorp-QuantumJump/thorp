import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * 최대 하락 구간 찾기(spec 005 R-05, FR-001·FR-010).
 *
 * <p><b>004가 `summary.maxDrawdownWindow`를 주면 그 값을 쓰고 이 함수를 지운다.</b> 이 spec은 값을 계산하지
 * 않는데(spec Assumptions), 이 함수가 그 유일한 예외다. 004의 `summary`는 최대 하락 폭 숫자만 주고 구간은
 * 주지 않는다.
 *
 * <p>새 값을 만들지 않고 이미 계산된 `drawdown` 계열에서 **날짜만 고른다.** 그리고 고른 저점의 값이
 * `summary.maxDrawdown`과 같을 때만 구간을 돌려준다. 다르면 이 계열로 요약 숫자를 설명할 수 없다는 뜻이라
 * `null`이고, 화면은 음영과 구간 보기를 생략한다(원칙 VI).
 *
 * <p>B04(그래프 음영)와 B07('손실이 커진 때 보기')이 같은 구간을 쓴다. 두 화면이 구간을 따로 찾으면 그래프에
 * 칠한 곳과 거래를 보여 주는 곳이 갈라진다.
 */

export type DrawdownWindow = {
  /** 저점 직전에 자산가치가 가장 높았던 날 */
  peakDate: string
  /** `drawdown`이 가장 낮은 날. 같은 값이 여러 날이면 처음 날이다 */
  troughDate: string
}

/** 읽을 수 없는 문자열은 `null`이다. 0으로 바꾸면 없는 하락이나 고점을 만든다. */
function readNumber(value: string | undefined): number | null {
  if (value === undefined || value.trim() === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

/**
 * @param points `/timeseries`의 계열. 날짜 오름차순이다
 * @param summaryMaxDrawdown `summary.maxDrawdown`. 0 이하의 비율 문자열이다
 * @returns 고점~저점. 회복 시점은 넣지 않는다(FR-002: 하락 폭은 고점에서 저점까지다). 하락이 없거나 요약과
 *   어긋나면 `null`
 */
export function findDrawdownWindow(
  points: readonly ResultTimeseriesPoint[],
  summaryMaxDrawdown: string,
): DrawdownWindow | null {
  const expected = readNumber(summaryMaxDrawdown)
  if (expected === null) return null

  let troughIndex = -1
  let troughValue = Number.POSITIVE_INFINITY
  points.forEach((point, index) => {
    const drawdown = readNumber(point.drawdown)
    // 같은 값이면 앞선 날을 둔다 — `<`라서 뒤의 같은 값이 덮어쓰지 않는다.
    if (drawdown !== null && drawdown < troughValue) {
      troughValue = drawdown
      troughIndex = index
    }
  })

  // 하락이 없으면 칠할 구간이 없다.
  if (troughIndex < 0 || troughValue >= 0) return null
  if (troughValue !== expected) return null

  let peakIndex = -1
  let peakEquity = Number.NEGATIVE_INFINITY
  for (let index = 0; index < troughIndex; index += 1) {
    const equity = readNumber(points[index].equity)
    // 같은 고점이 다시 오면 뒤의 날을 둔다 — 하락은 마지막으로 고점에 있던 날부터 시작한다.
    if (equity !== null && equity >= peakEquity) {
      peakEquity = equity
      peakIndex = index
    }
  }
  if (peakIndex < 0) return null

  return { peakDate: points[peakIndex].date, troughDate: points[troughIndex].date }
}
