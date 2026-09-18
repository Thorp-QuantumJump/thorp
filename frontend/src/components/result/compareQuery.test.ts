import { describe, expect, it } from 'vitest'
import { buildResultPath, parseCompareQuery } from './compareQuery'

const base = 'a'.repeat(32)
const target = 'b'.repeat(32)
describe('비교 주소', () => {
  it('공개 토큰과 탐색 주소를 손실 없이 왕복한다', () => {
    const path = buildResultPath(base, { compareToken: target, searchId: 'SEARCH / 한글&' })
    expect(parseCompareQuery(path.slice(path.indexOf('?')))).toEqual({
      compareToken: target,
      searchId: 'SEARCH / 한글&',
    })
    expect(buildResultPath(base)).toBe('/results/' + base)
  })
  it.each(['BT-123', '../secret', 'A'.repeat(32), 'a'.repeat(31), ''])(
    '잘못된 토큰 %s는 무시한다',
    (value) => {
      expect(parseCompareQuery('?compare=' + encodeURIComponent(value)).compareToken).toBeNull()
    },
  )
})
