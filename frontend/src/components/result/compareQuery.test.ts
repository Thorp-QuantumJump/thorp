import { describe, expect, it } from 'vitest'
import { MAX_COMPARE_TARGETS, buildResultPath, parseCompareQuery } from './compareQuery'

const base = 'a'.repeat(32)
const target = 'b'.repeat(32)
const second = 'c'.repeat(32)
describe('비교 주소', () => {
  it('공개 토큰 목록과 탐색 주소를 손실 없이 왕복한다', () => {
    const path = buildResultPath(base, {
      compareTokens: [target, second],
      searchId: 'SEARCH / 한글&',
    })
    expect(parseCompareQuery(path.slice(path.indexOf('?')))).toEqual({
      compareTokens: [target, second],
      searchId: 'SEARCH / 한글&',
    })
    expect(buildResultPath(base)).toBe('/results/' + base)
    expect(buildResultPath(base, { compareTokens: [] })).toBe('/results/' + base)
  })
  it('같은 토큰은 한 번만 두고 상한을 넘는 토큰은 뒤에서 자른다', () => {
    const tokens = ['b', 'c', 'd', 'e'].map((char) => char.repeat(32))
    expect(parseCompareQuery('?compare=' + [tokens[0], ...tokens].join(',')).compareTokens).toEqual(
      tokens.slice(0, MAX_COMPARE_TARGETS),
    )
  })
  it.each(['BT-123', '../secret', 'A'.repeat(32), 'a'.repeat(31), ''])(
    '잘못된 토큰 %s는 무시한다',
    (value) => {
      expect(parseCompareQuery('?compare=' + encodeURIComponent(value)).compareTokens).toEqual([])
      expect(
        parseCompareQuery('?compare=' + encodeURIComponent(value + ',' + target)).compareTokens,
      ).toEqual([target])
    },
  )
})
