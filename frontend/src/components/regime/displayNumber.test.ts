import { describe, expect, it } from 'vitest'
import { formatDecimal, formatShare } from './displayNumber'

describe('경제환경 검색 숫자 표기', () => {
  it('소수 첫째 자리까지 보인다', () => {
    expect(formatDecimal('1.823757636752')).toBe('1.8')
    expect(formatDecimal('0.593040887402')).toBe('0.6')
    expect(formatDecimal('3.000000000000')).toBe('3')
    expect(formatDecimal('-0.040000000000')).toBe('0')
    expect(formatDecimal('24137983294.000000000000')).toBe('24,137,983,294')
  })

  it('비율은 백분율로 보인다', () => {
    expect(formatShare('0.396402864300')).toBe('39.6%')
    expect(formatShare('1.000000000000')).toBe('100%')
    expect(formatShare('0.000400000000')).toBe('0%')
  })

  it('숫자가 아니면 받은 그대로 둔다', () => {
    expect(formatDecimal('자료 없음')).toBe('자료 없음')
  })
})
