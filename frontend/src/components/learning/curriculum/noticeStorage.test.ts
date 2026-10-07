import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DISMISSED_NOTICES_STORAGE_KEY, dismissNotices, readDismissedNotices } from './noticeStorage'

/** 013 T136·T141. 닫은 공개 안내를 탭 동안만 기억한다(FR-031 '공개 안내', quickstart 11-2). */

describe('noticeStorage', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('닫은 줄이 없는 새 탭은 빈 목록이다', () => {
    expect(readDismissedNotices()).toEqual([])
  })

  it('닫은 entryKey를 앞서 닫은 줄과 합쳐 sessionStorage에만 적는다', () => {
    const local = vi.spyOn(window.localStorage, 'setItem')

    dismissNotices(['stage:harbor-basics'])
    const next = dismissNotices(['stage:swell-and-risk', 'stage:harbor-basics'])

    expect(next).toEqual(['stage:harbor-basics', 'stage:swell-and-risk'])
    expect(readDismissedNotices()).toEqual(next)
    expect(JSON.parse(window.sessionStorage.getItem(DISMISSED_NOTICES_STORAGE_KEY)!)).toEqual({
      version: 1,
      entryKeys: next,
    })
    expect(local).not.toHaveBeenCalled()
  })

  it('읽을 수 없거나 모르는 판이면 닫은 줄이 없는 것으로 본다', () => {
    for (const raw of ['{', '[]', '{"version":2,"entryKeys":["a"]}', '{"version":1,"entryKeys":"a"}']) {
      window.sessionStorage.setItem(DISMISSED_NOTICES_STORAGE_KEY, raw)
      expect(readDismissedNotices()).toEqual([])
    }
  })

  it('글자가 아닌 항목은 버리고 나머지를 읽는다', () => {
    window.sessionStorage.setItem(
      DISMISSED_NOTICES_STORAGE_KEY,
      JSON.stringify({ version: 1, entryKeys: ['article:dock-fees', 3, null] }),
    )

    expect(readDismissedNotices()).toEqual(['article:dock-fees'])
  })

  it('저장소가 막혀 있어도 던지지 않고 이 화면 안에서 쓸 목록을 돌려준다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    expect(dismissNotices(['stage:harbor-basics'])).toEqual(['stage:harbor-basics'])
    expect(readDismissedNotices()).toEqual([])
  })
})
