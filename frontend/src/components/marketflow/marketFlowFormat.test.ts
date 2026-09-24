import { describe, expect, it } from 'vitest'
import type { PathPoint } from '@/types/marketFlow'
import { buildAxisDomain, recentDirection } from './marketFlowFormat'

/**
 * 실제 지표 값으로 검증한다. 꾸며낸 숫자로는 "환율이 평평해 보인다"는 문제가 재현되지 않는다.
 * 값의 출처는 백엔드 예시 자료(`macro-indicators-example-v1.json`)다.
 */
describe('3개월 경로의 세로축 범위', () => {
  describe('수준을 나타내는 지표는 0을 뺀다', () => {
    it.each([
      ['원/달러 환율', [1351.1, 1350.2, 1378.5]],
      ['국고채 3년', [3.02, 3.11, 3.18]],
      ['실업률', [2.8, 2.8, 2.9]],
      ['두바이유', [78.2, 76.8, 75.4]],
      ['소비자물가', [2.6, 2.4, 2.2]],
    ])('%s는 0에서 시작하지 않는다', (_name, values) => {
      const { domain, includesZero } = buildAxisDomain(values)

      expect(includesZero).toBe(false)
      expect(domain[0]).toBeGreaterThan(0)
      // 자료가 축 안에 들어오되 가장자리에 붙지 않는다.
      expect(domain[0]).toBeLessThan(Math.min(...values))
      expect(domain[1]).toBeGreaterThan(Math.max(...values))
    })

    it('환율 27원 변동이 축 높이의 절반을 넘게 쓴다', () => {
      const values = [1351.1, 1350.2, 1378.5]
      const { domain } = buildAxisDomain(values)

      const used = (Math.max(...values) - Math.min(...values)) / (domain[1] - domain[0])
      // 0부터 그리면 이 값이 0.02였다. 그래서 평평한 선으로 보였다.
      expect(used).toBeGreaterThan(0.5)
    })
  })

  describe('0이 뜻을 갖는 자리에서는 0을 남긴다', () => {
    it('부호가 뒤집히면 0이 축 안에 있다', () => {
      // 소매판매액지수. 감소에서 증가로 돌아선 자리가 0이다.
      const { domain, includesZero } = buildAxisDomain([-0.4, 0.2, 0.5])

      expect(includesZero).toBe(true)
      expect(domain[0]).toBeLessThanOrEqual(0)
      expect(domain[1]).toBeGreaterThanOrEqual(0)
    })

    it('모두 음수여도 0까지가 가까우면 0을 남긴다', () => {
      // 건설기성액. 0이 보여야 '감소 폭이 줄었다'가 읽힌다.
      expect(buildAxisDomain([-1.2, -0.8]).includesZero).toBe(true)
    })

    it.each([
      ['전산업생산', [1.2, 1.0, 0.8]],
      ['광공업생산', [2.1, 1.5, 0.9]],
      ['수출', [3.2, 4.0, 4.8]],
      ['취업자 수', [1.4, 1.0, 0.7]],
    ])('%s는 0을 넣어도 변화가 읽히므로 0을 남긴다', (_name, values) => {
      expect(buildAxisDomain(values).includesZero).toBe(true)
    })

    /** 0을 포함할 때도 변화가 축의 30% 가까이를 쓴다. 그것이 포함 기준이다. */
    it('0을 포함해도 변화가 축의 28% 이상을 쓴다', () => {
      for (const values of [[1.2, 1.0, 0.8], [2.1, 1.5, 0.9], [3.2, 4.0, 4.8]]) {
        const { domain } = buildAxisDomain(values)
        const used = (Math.max(...values) - Math.min(...values)) / (domain[1] - domain[0])
        expect(used).toBeGreaterThanOrEqual(0.28)
      }
    })
  })

  describe('가장자리', () => {
    it('세 값이 모두 같으면 축이 한 점으로 무너지지 않는다', () => {
      const { domain } = buildAxisDomain([2.9, 2.9, 2.9])

      expect(domain[1]).toBeGreaterThan(domain[0])
      expect(domain[0]).toBeLessThan(2.9)
      expect(domain[1]).toBeGreaterThan(2.9)
    })

    it('값이 모두 0이어도 축이 무너지지 않는다', () => {
      const { domain } = buildAxisDomain([0, 0, 0])

      expect(domain[1]).toBeGreaterThan(domain[0])
    })

    it('관측값이 하나도 없으면 기본 축을 돌려준다', () => {
      expect(buildAxisDomain([])).toEqual({ domain: [0, 1], includesZero: true })
    })

    it('값이 하나뿐이어도 축이 무너지지 않는다', () => {
      const { domain } = buildAxisDomain([1378.5])

      expect(domain[1]).toBeGreaterThan(domain[0])
    })
  })
})

describe('recentDirection', () => {
  const path = (a: number | null, b: number | null, c: number | null) => [
    { month: '2026-06', value: a },
    { month: '2026-07', value: b },
    { month: '2026-08', value: c },
  ] as [PathPoint, PathPoint, PathPoint]

  it('값이 있는 마지막 두 달로 오르내림을 정한다', () => {
    expect(recentDirection(path(1, 2, 3))).toBe('up')
    expect(recentDirection(path(3, 2, 1))).toBe('down')
    expect(recentDirection(path(1, 2, 2))).toBeUndefined()
    // 마지막 달이 비면 그 앞 두 달을 견준다.
    expect(recentDirection(path(3, 1, null))).toBe('down')
    expect(recentDirection(path(null, null, 1))).toBeUndefined()
  })
})
