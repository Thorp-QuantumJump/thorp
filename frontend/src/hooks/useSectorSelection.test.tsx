import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SECTOR_SELECTION_STORAGE_KEY, useSectorSelection } from './useSectorSelection'

/**
 * 002 경계의 읽기 규칙을 고정한다(T030).
 *
 * <p>이 훅은 002가 확정한 섹터·비중을 읽는 유일한 통로이고, 저장소와 질의 문자열이라는 두 출처를
 * 함께 본다. <b>어느 쪽을 언제 믿는지가 이 파일의 전부</b>이므로 그 규칙을 검사로 남긴다 — 규칙이
 * 주석에만 있으면 다음 사람이 우선순위를 바꿔도 아무것도 실패하지 않는다.
 */

function stored(value: unknown): void {
  window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, JSON.stringify(value))
}

function renderSelection(entry = '/') {
  return renderHook(() => useSectorSelection(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[entry]}>{children}</MemoryRouter>
    ),
  })
}

afterEach(() => {
  window.sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('useSectorSelection', () => {
  it('앞 단계를 아직 마치지 않았으면 null 을 돌려준다', () => {
    const { result } = renderSelection()

    // 임의의 섹터를 골라 대신 채우지 않는다. 화면이 안내를 보일 수 있게 없음을 그대로 알린다.
    expect(result.current).toBeNull()
  })

  it('저장된 선택만 있으면 비중까지 읽는다', () => {
    stored({
      mappingVersion: 'MAP-TEST-1',
      items: [
        { sectorId: 'SEC-A', instrumentId: 'INS-A', targetWeight: 0.6 },
        { sectorId: 'SEC-B', instrumentId: 'INS-B', targetWeight: 0.4 },
      ],
    })

    const { result } = renderSelection()

    expect(result.current?.mappingVersion).toBe('MAP-TEST-1')
    expect(result.current?.items).toHaveLength(2)
    expect(result.current?.items[0].targetWeight).toBe(0.6)
  })

  it('질의 문자열만 있으면 비중을 null 로 둔다', () => {
    const { result } = renderSelection('/?sectors=SEC-A,SEC-B&instruments=INS-A,INS-B')

    // 링크에는 비중이 실리지 않는다. 0으로 채우면 사용자가 정한 적 없는 비중을 화면이 말하게 된다.
    expect(result.current?.mappingVersion).toBeNull()
    expect(result.current?.items).toEqual([
      { sectorId: 'SEC-A', instrumentId: 'INS-A', targetWeight: null },
      { sectorId: 'SEC-B', instrumentId: 'INS-B', targetWeight: null },
    ])
  })

  it('질의 문자열과 저장된 섹터가 같으면 저장된 비중을 쓴다', () => {
    stored({
      mappingVersion: 'MAP-TEST-1',
      items: [
        { sectorId: 'SEC-A', instrumentId: 'INS-A', targetWeight: 0.7 },
        { sectorId: 'SEC-B', instrumentId: 'INS-B', targetWeight: 0.3 },
      ],
    })

    // 순서가 달라도 같은 집합이다.
    const { result } = renderSelection('/?sectors=SEC-B,SEC-A')

    expect(result.current?.mappingVersion).toBe('MAP-TEST-1')
    expect(result.current?.items[0].targetWeight).toBe(0.7)
  })

  it('질의 문자열이 다른 섹터를 가리키면 저장된 비중을 얹지 않는다', () => {
    stored({
      mappingVersion: 'MAP-TEST-1',
      items: [{ sectorId: 'SEC-A', instrumentId: 'INS-A', targetWeight: 1 }],
    })

    const { result } = renderSelection('/?sectors=SEC-C&instruments=INS-C')

    // 링크로 다른 섹터를 열었는데 앞선 세션의 비중이 따라오면 화면이 틀린 비중을 보인다.
    expect(result.current?.mappingVersion).toBeNull()
    expect(result.current?.items).toEqual([
      { sectorId: 'SEC-C', instrumentId: 'INS-C', targetWeight: null },
    ])
  })

  it('모양이 어긋난 저장 자료는 한 항목만 어긋나도 전체를 버린다', () => {
    stored({
      mappingVersion: 'MAP-TEST-1',
      items: [
        { sectorId: 'SEC-A', instrumentId: 'INS-A', targetWeight: 1 },
        { sectorId: 'SEC-B' },
      ],
    })

    // 어긋난 항목만 빼면 비중의 합이 달라진 자료를 온전한 것처럼 쓰게 된다.
    expect(renderSelection().result.current).toBeNull()
  })

  it('저장소를 쓸 수 없는 환경에서도 없음으로 다룬다', () => {
    // 사생활 보호 모드 등에서는 접근 자체가 예외를 던진다. 화면이 그것으로 깨지지 않아야 한다.
    vi.spyOn(window.sessionStorage, 'getItem').mockImplementation(() => {
      throw new Error('storage is not available')
    })

    expect(renderSelection().result.current).toBeNull()
    expect(renderSelection('/?sectors=SEC-A').result.current?.items).toHaveLength(1)
  })
})
