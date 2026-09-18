import { describe, expect, it } from 'vitest'
import { findDrawdownWindow } from '@/components/result/drawdownWindow'
import { makeEquitySeries } from '@/test/resultFixtures'
import type { ResultTimeseriesPoint } from '@/types/result'

/** 날짜·자산가치·하락 폭만 정해 손으로 만든 계열 */
function series(rows: [string, number, number][]): ResultTimeseriesPoint[] {
  return rows.map(([date, equity, drawdown]) => ({
    date,
    equity: equity.toFixed(2),
    cumulativeReturn: '0.000000',
    drawdown: drawdown.toFixed(6),
  }))
}

describe('findDrawdownWindow', () => {
  it('고점과 저점의 날짜를 돌려준다', () => {
    const points = makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 })

    const window = findDrawdownWindow(points, points[55].drawdown)

    expect(window).toEqual({ peakDate: points[40].date, troughDate: points[55].date })
  })

  it('보고된 최대 하락 폭과 저점이 어긋나면 음영을 포기한다', () => {
    const points = makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 })

    expect(findDrawdownWindow(points, '-0.030000')).toBeNull()
  })

  it('가장 낮은 하락 폭이 여러 날이면 처음 날을 저점으로 본다', () => {
    const points = series([
      ['2025-01-02', 10_000, 0],
      ['2025-01-03', 10_500, 0],
      ['2025-01-06', 9_975, -0.05],
      ['2025-01-07', 9_975, -0.05],
      ['2025-01-08', 10_200, -0.028571],
    ])

    expect(findDrawdownWindow(points, '-0.050000')).toEqual({
      peakDate: '2025-01-03',
      troughDate: '2025-01-06',
    })
  })

  it('고점 값이 여러 날이면 마지막 날부터 구간이 시작한다', () => {
    const points = series([
      ['2025-01-02', 10_500, 0],
      ['2025-01-03', 10_500, 0],
      ['2025-01-06', 9_975, -0.05],
    ])

    expect(findDrawdownWindow(points, '-0.050000')).toEqual({
      peakDate: '2025-01-03',
      troughDate: '2025-01-06',
    })
  })

  it('점이 1,900개여도 같은 구간을 찾는다', () => {
    const points = makeEquitySeries({ points: 1900, peakIndex: 600, troughIndex: 900 })

    const window = findDrawdownWindow(points, points[900].drawdown)

    expect(window).toEqual({ peakDate: points[600].date, troughDate: points[900].date })
    expect(points).toHaveLength(1900)
  })

  it('저점 뒤 회복한 날은 구간에 들어가지 않는다', () => {
    const points = makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 })

    const window = findDrawdownWindow(points, points[55].drawdown)

    // 마지막 날은 저점보다 자산가치가 높다. 그런데도 구간의 끝은 저점이다.
    expect(Number(points[128].equity)).toBeGreaterThan(Number(points[55].equity))
    expect(window?.troughDate).toBe(points[55].date)
  })

  it('하락이 없었다고 보고된 결과에는 구간이 없다', () => {
    const points = series([
      ['2025-01-02', 10_000, 0],
      ['2025-01-03', 10_500, 0],
    ])

    expect(findDrawdownWindow(points, '0.000000')).toBeNull()
  })

  it('최대 하락 폭을 읽을 수 없으면 구간이 없다', () => {
    const points = makeEquitySeries({ points: 40, peakIndex: 10, troughIndex: 20 })

    expect(findDrawdownWindow(points, null)).toBeNull()
    expect(findDrawdownWindow(points, '')).toBeNull()
    expect(findDrawdownWindow(points, '계산되지 않음')).toBeNull()
  })

  it('시계열이 비어 있으면 구간이 없다', () => {
    expect(findDrawdownWindow([], '-0.050000')).toBeNull()
  })
})
