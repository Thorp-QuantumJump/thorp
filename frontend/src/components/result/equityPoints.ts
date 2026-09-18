import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * 시계열 점의 자산가치를 읽는 규칙. 그래프(`EquityChart`)와 그래프 자리(`ResultPage`)가 같은 규칙으로
 * "그릴 것이 있는가"를 판단해야 둘의 말이 어긋나지 않는다.
 */

/** 자산가치가 수로 읽히면 그 수, 아니면 `null`. 읽을 수 없는 값을 0으로 바꾸지 않는다(원칙 VI) */
export function readEquity(point: ResultTimeseriesPoint): number | null {
  const value = point.equity
  if (typeof value !== 'string' || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 그래프로 그릴 자산가치가 하나라도 있는가.
 *
 * 점이 없거나 모든 날의 자산가치가 비어 있으면 `false`다. 이때 축만 있는 빈 그래프를 그리면 "돌았고
 * 비어 있었다"는 거짓이 되므로, 화면은 그래프 대신 그 사실을 한 문장으로 알린다.
 */
export function hasDrawableEquity(points: ResultTimeseriesPoint[]): boolean {
  return points.some((point) => readEquity(point) != null)
}
