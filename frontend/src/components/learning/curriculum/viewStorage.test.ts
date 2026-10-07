import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LEARNING_VIEW_STORAGE_KEY, readStoredView, writeStoredView } from './viewStorage'

/** 013 T136·T141. 고른 보기를 탭 동안만 기억한다(FR-023, quickstart 11-1). */

describe('viewStorage', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('기억한 값이 없는 새 탭은 커리큘럼 보기다', () => {
    expect(readStoredView()).toBe('curriculum')
  })

  it('고른 보기를 내용 버전과 함께 sessionStorage에만 적고 다시 읽는다', () => {
    // jsdom의 저장소 메서드는 Storage.prototype에 있다. 인스턴스에 감시를 걸면 그 이름의 저장 항목이 생길 뿐 호출을 잡지
    // 못하므로, prototype에 걸고 누가 불렀는지(this)를 본다.
    const setItem = vi.spyOn(Storage.prototype, 'setItem')

    writeStoredView('list')

    expect(setItem).toHaveBeenCalledTimes(1)
    expect(setItem.mock.contexts[0]).toBe(window.sessionStorage)
    expect(window.localStorage.getItem(LEARNING_VIEW_STORAGE_KEY)).toBeNull()
    expect(JSON.parse(window.sessionStorage.getItem(LEARNING_VIEW_STORAGE_KEY)!)).toEqual({
      version: 1,
      view: 'list',
    })
    expect(readStoredView()).toBe('list')
  })

  it('읽을 수 없거나 모르는 판·모르는 보기는 커리큘럼 보기로 시작한다', () => {
    for (const raw of ['{', '"list"', '{"version":2,"view":"list"}', '{"version":1,"view":"grid"}']) {
      window.sessionStorage.setItem(LEARNING_VIEW_STORAGE_KEY, raw)
      expect(readStoredView()).toBe('curriculum')
    }
  })

  it('저장소가 막혀 있어도 던지지 않는다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    expect(() => writeStoredView('list')).not.toThrow()
    expect(readStoredView()).toBe('curriculum')
  })
})
