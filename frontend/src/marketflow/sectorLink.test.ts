import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  MARKET_FLOW_SECTOR_KEY,
  clearSelectedSector,
  readSelectedSector,
  saveSelectedSector,
} from './sectorLink'

/** 저장소를 쓸 수 없는 환경(시크릿 창, 저장소 차단)을 흉내낸다. */
function breakSessionStorage(): void {
  vi.spyOn(window.sessionStorage.__proto__, 'getItem').mockImplementation(() => {
    throw new DOMException('denied', 'SecurityError')
  })
  vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
    throw new DOMException('denied', 'SecurityError')
  })
  vi.spyOn(window.sessionStorage.__proto__, 'removeItem').mockImplementation(() => {
    throw new DOMException('denied', 'SecurityError')
  })
}

describe('시장 흐름 섹터 선택 보관', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('저장한 섹터를 그대로 돌려준다', () => {
    saveSelectedSector('SEMICONDUCTOR')

    expect(readSelectedSector()).toBe('SEMICONDUCTOR')
    expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBe('SEMICONDUCTOR')
  })

  it('아무것도 고르지 않았으면 null이다', () => {
    expect(readSelectedSector()).toBeNull()
  })

  it('선택을 지우면 키가 남지 않는다', () => {
    saveSelectedSector('SEMICONDUCTOR')
    clearSelectedSector()

    expect(readSelectedSector()).toBeNull()
    // 빈 문자열로 남기면 다음 읽기가 '선택 있음'으로 보인다.
    expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBeNull()
  })

  // sessionStorage는 사용자가 편집할 수 있는 자리다. 들어 있는 값을 믿지 않는다.
  it.each([
    ['빈 문자열', ''],
    ['공백뿐인 값', '   '],
  ])('%s가 들어 있으면 선택으로 세지 않는다', (_label, stored) => {
    window.sessionStorage.setItem(MARKET_FLOW_SECTOR_KEY, stored)

    expect(readSelectedSector()).toBeNull()
  })

  it.each([
    ['빈 문자열', ''],
    ['공백뿐인 값', '  '],
    ['문자열이 아닌 값', 17],
    ['undefined', undefined],
  ])('%s를 저장하려 하면 선택을 지운다', (_label, given) => {
    saveSelectedSector('SEMICONDUCTOR')

    saveSelectedSector(given)

    expect(readSelectedSector()).toBeNull()
  })

  it('앞뒤 공백은 떼고 보관한다', () => {
    saveSelectedSector('  SEMICONDUCTOR  ')

    expect(readSelectedSector()).toBe('SEMICONDUCTOR')
  })

  /**
   * 저장이 안 되는 것은 불편이고 화면이 죽는 것은 고장이다. 셋 다 예외를 삼켜야 한다.
   * 여기서 막지 않으면 시크릿 창에서 시장 흐름 화면 전체가 오류 경계로 떨어진다.
   */
  it('저장소를 쓸 수 없어도 예외가 새지 않고 읽기는 null이다', () => {
    breakSessionStorage()

    expect(() => saveSelectedSector('SEMICONDUCTOR')).not.toThrow()
    expect(() => clearSelectedSector()).not.toThrow()
    expect(readSelectedSector()).toBeNull()
  })
})
