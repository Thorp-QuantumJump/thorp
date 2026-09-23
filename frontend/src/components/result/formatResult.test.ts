import { describe, expect, it } from 'vitest'
import { formatRatio, ratioDirection } from './formatResult'

describe('ratioDirection', () => {
  it('보이는 값의 부호로 오르내림을 정한다', () => {
    expect(ratioDirection('0.166652')).toBe('up')
    expect(ratioDirection('-0.041757')).toBe('down')
  })

  it('반올림해 0.00%로 보이는 값에는 방향을 주지 않는다', () => {
    // 화면이 "0.00%"라고 쓰는데 빨강·파랑이 붙으면 글과 색이 서로 다른 말을 한다.
    expect(formatRatio('-0.00004')).toBe('0.00%')
    expect(ratioDirection('-0.00004')).toBeUndefined()
    expect(ratioDirection('0.00004')).toBeUndefined()
    expect(ratioDirection('0')).toBeUndefined()
  })

  it('읽을 수 없는 값에는 방향을 주지 않는다', () => {
    expect(ratioDirection('')).toBeUndefined()
    expect(ratioDirection('N/A')).toBeUndefined()
  })
})
