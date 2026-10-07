import { describe, expect, it } from 'vitest'
import { articlePath, LEARN_PATH, stageHash, stageLocation, stageNumberFromHash } from './learningPaths'

/** 013 T139·T203. 배우기 주소를 한곳에서 만든다. */

describe('learningPaths', () => {
  it('글 주소는 글 식별자를 주소 조각으로 바꿔 붙인다', () => {
    expect(articlePath('tide-reading')).toBe('/learn/tide-reading')
    expect(articlePath('a b/c')).toBe('/learn/a%20b%2Fc')
  })

  it("단계 자리는 배우기 목록의 '#stage-n'이다", () => {
    expect(stageHash(3)).toBe('#stage-3')
    expect(stageLocation(2)).toEqual({ pathname: LEARN_PATH, hash: '#stage-2' })
  })

  it('#stage-n 모양만 단계 번호로 읽는다', () => {
    expect(stageNumberFromHash('#stage-3')).toBe(3)
    expect(stageNumberFromHash('#stage-')).toBeNull()
    expect(stageNumberFromHash('#learning-change-log')).toBeNull()
    expect(stageNumberFromHash('')).toBeNull()
  })
})
