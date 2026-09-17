import { describe, expect, it } from 'vitest'
import { findDrawdownWindow } from './drawdownWindow'
import { makeEquitySeries } from '@/test/resultFixtures'
import type { ResultTimeseriesPoint } from '@/types/result'

/** 계열의 저점 값을 그대로 요약 값으로 쓴다. 004가 같은 계열에서 요약을 냈다는 가정이다. */
function troughOf(points: readonly ResultTimeseriesPoint[]): string {
  return points.reduce((lowest, point) =>
    Number(point.drawdown) < Number(lowest.drawdown) ? point : lowest,
  ).drawdown
}

function point(date: string, equity: string, drawdown: string): ResultTimeseriesPoint {
  return { date, equity, cumulativeReturn: '0', drawdown }
}

describe('findDrawdownWindow (T055, R-05)', () => {
  it('합성 계열에서 고점과 저점의 날짜를 고른다', () => {
    const points = makeEquitySeries({ points: 60, peakIndex: 20, troughIndex: 35 })

    expect(findDrawdownWindow(points, troughOf(points))).toEqual({
      peakDate: points[20].date,
      troughDate: points[35].date,
    })
  })

  it('저점 값이 summary.maxDrawdown과 다르면 구간을 내지 않는다', () => {
    const points = makeEquitySeries({ points: 60, peakIndex: 20, troughIndex: 35 })

    expect(findDrawdownWindow(points, '-0.041757')).toBeNull()
  })

  it('표기 자릿수만 다른 같은 값은 같은 값으로 본다', () => {
    const points = [
      point('2025-01-01', '100.00', '0.000000'),
      point('2025-01-02', '90.00', '-0.100000'),
    ]

    expect(findDrawdownWindow(points, '-0.1')).toEqual({
      peakDate: '2025-01-01',
      troughDate: '2025-01-02',
    })
  })

  it('저점이 여러 날이면 처음 날을 쓴다', () => {
    const points = [
      point('2025-01-01', '100.00', '0.000000'),
      point('2025-01-02', '90.00', '-0.100000'),
      point('2025-01-03', '95.00', '-0.050000'),
      point('2025-01-06', '90.00', '-0.100000'),
    ]

    expect(findDrawdownWindow(points, '-0.100000')?.troughDate).toBe('2025-01-02')
  })

  it('1,900점 계열에서도 같은 구간을 고른다', () => {
    const points = makeEquitySeries({ points: 1_900, peakIndex: 700, troughIndex: 1_300 })

    expect(findDrawdownWindow(points, troughOf(points))).toEqual({
      peakDate: points[700].date,
      troughDate: points[1_300].date,
    })
  })

  it('회복한 날은 구간에 넣지 않는다', () => {
    const points = [
      point('2025-01-01', '100.00', '0.000000'),
      point('2025-01-02', '80.00', '-0.200000'),
      point('2025-01-03', '100.00', '0.000000'),
      point('2025-01-06', '110.00', '0.000000'),
    ]

    const window = findDrawdownWindow(points, '-0.200000')

    expect(window).toEqual({ peakDate: '2025-01-01', troughDate: '2025-01-02' })
    expect(Object.values(window ?? {})).not.toContain('2025-01-03')
  })

  it('하락이 없으면 칠할 구간이 없다', () => {
    const points = [
      point('2025-01-01', '100.00', '0.000000'),
      point('2025-01-02', '101.00', '0.000000'),
    ]

    expect(findDrawdownWindow(points, '0.000000')).toBeNull()
  })

  it('읽을 수 없는 요약 값이나 빈 계열이면 구간을 지어내지 않는다', () => {
    const points = makeEquitySeries({ points: 30, peakIndex: 5, troughIndex: 10 })

    expect(findDrawdownWindow(points, '')).toBeNull()
    expect(findDrawdownWindow([], '-0.1')).toBeNull()
  })
})
