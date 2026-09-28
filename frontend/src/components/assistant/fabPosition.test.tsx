import { beforeEach, describe, expect, it } from 'vitest'
import {
  FAB_POSITION_KEY,
  clampFabY,
  defaultFabPosition,
  loadFabPosition,
  saveFabPosition,
  snapSide,
} from './fabPosition'

/** spec 015 FR-002. 저장소가 없거나 값이 이상해도 버튼은 항상 화면 안 어딘가에 있다. */
describe('fabPosition', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('기본 자리는 우측 하단이고 세로는 화면 안으로 자른다', () => {
    expect(defaultFabPosition(800)).toEqual({ side: 'right', y: 720 })
    expect(clampFabY(-50, 800)).toBe(16)
    expect(clampFabY(5000, 800)).toBe(728)
  })

  it('놓은 자리가 가운데보다 왼쪽이면 왼쪽 여백이다', () => {
    expect(snapSide(100, 1280)).toBe('left')
    expect(snapSide(1000, 1280)).toBe('right')
  })

  it('저장한 자리를 읽고, 화면이 작아졌으면 안으로 당긴다', () => {
    saveFabPosition({ side: 'left', y: 700 })
    expect(loadFabPosition(800)).toEqual({ side: 'left', y: 700 })
    expect(loadFabPosition(400)).toEqual({ side: 'left', y: 328 })
  })

  it('망가진 값은 기본 자리로 돌아간다', () => {
    window.localStorage.setItem(FAB_POSITION_KEY, '{"side":"top","y":"x"}')
    expect(loadFabPosition(800)).toEqual({ side: 'right', y: 720 })
    window.localStorage.setItem(FAB_POSITION_KEY, 'not json')
    expect(loadFabPosition(800)).toEqual({ side: 'right', y: 720 })
  })
})
