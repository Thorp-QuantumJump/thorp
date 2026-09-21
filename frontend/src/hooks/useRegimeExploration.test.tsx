import { renderHook, act } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { readExploration, useRegimeExploration } from './useRegimeExploration'

const base = 'a'.repeat(32)

beforeEach(() => {
  window.sessionStorage.clear()
})

describe('011 탐색 상태', () => {
  it('저장된 값이 없으면 첫 진입이다', () => {
    const { result } = renderHook(() => useRegimeExploration(base, null))
    expect(result.current.isReturn).toBe(false)
    expect(result.current.state).toBeNull()
  })

  it('주소에 snapshot이 없고 저장된 값이 있으면 복귀로 본다', () => {
    window.sessionStorage.setItem(
      `thorp.regimeLink.exploration.${base}`,
      JSON.stringify({ snapshotKey: 'SNAP', entryPath: 'STRATEGY_RESULT', full: false }),
    )
    const { result } = renderHook(() => useRegimeExploration(base, null))
    expect(result.current.isReturn).toBe(true)
    expect(result.current.state?.snapshotKey).toBe('SNAP')
  })

  it('다른 base의 값은 쓰지 않는다', () => {
    window.sessionStorage.setItem(
      `thorp.regimeLink.exploration.${'c'.repeat(32)}`,
      JSON.stringify({ snapshotKey: 'OTHER', entryPath: 'STRATEGY_RESULT', full: false }),
    )
    const { result } = renderHook(() => useRegimeExploration(base, null))
    expect(result.current.isReturn).toBe(false)
  })

  it('기억한 값은 새로고침 뒤에도 남는다', () => {
    const { result } = renderHook(() => useRegimeExploration(base, null))
    act(() => {
      result.current.remember({
        snapshotKey: 'SNAP',
        entryPath: 'STRATEGY_RESULT',
        full: true,
        linkSearchId: 'RLS-1',
      })
    })
    expect(readExploration(base)).toMatchObject({ snapshotKey: 'SNAP', full: true, linkSearchId: 'RLS-1' })
  })

  it('저장 값이 깨져 있으면 첫 진입으로 떨어진다', () => {
    window.sessionStorage.setItem(`thorp.regimeLink.exploration.${base}`, '{{')
    const { result } = renderHook(() => useRegimeExploration(base, null))
    expect(result.current.isReturn).toBe(false)
  })
})
