import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeSearch, getRegimeSearchById } from '@/api/regime'
import { regimeResult } from '@/test/regimeFixtures'
import type { RegimeSearchResult } from '@/types/regime'
import { useRegimeSearch } from './useRegimeSearch'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeSearchById: vi.fn() }))

function pending() {
  let resolve!: (value: RegimeSearchResult) => void
  const promise = new Promise<RegimeSearchResult>((done) => { resolve = done })
  return { promise, resolve }
}

describe('경제환경 검색 훅', () => {
  beforeEach(() => vi.resetAllMocks())

  it('이전 분야의 늦은 응답이 현재 결과를 덮지 않는다', async () => {
    const first = pending()
    const second = pending()
    vi.mocked(getRegimeSearch).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { result, rerender } = renderHook(
      ({ field }: { field: string }) => useRegimeSearch({ mode: 'field', field }),
      { initialProps: { field: 'old' } },
    )
    rerender({ field: 'new' })
    await act(async () => second.resolve(regimeResult({ field: { id: 'new', name: '새 분야' } })))
    await waitFor(() => expect(result.current.result?.field?.id).toBe('new'))
    await act(async () => first.resolve(regimeResult({ field: { id: 'old', name: '옛 분야' } })))
    expect(result.current.result?.field?.id).toBe('new')
  })

  it('더 보기 뒤 다른 분야를 찾으면 처음 세 건부터 조회한다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult())
    const { result, rerender } = renderHook(
      ({ field }: { field: string }) => useRegimeSearch({ mode: 'field', field }),
      { initialProps: { field: 'old' } },
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    act(() => result.current.showMore())
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'field', field: 'old', baselineMonth: undefined, limit: 10 },
      expect.any(AbortSignal),
    ))
    rerender({ field: 'new' })
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'field', field: 'new', baselineMonth: undefined, limit: 3 },
      expect.any(AbortSignal),
    ))
  })

  it('저장된 검색 식별자가 있으면 폼 질의 대신 그 판을 고정 조회한다(006 R17)', async () => {
    vi.mocked(getRegimeSearchById).mockResolvedValue(regimeResult({ searchId: 'saved-1' }))
    const { result } = renderHook(() => useRegimeSearch(null, 'saved-1'))
    await waitFor(() => expect(result.current.result?.searchId).toBe('saved-1'))
    expect(vi.mocked(getRegimeSearchById)).toHaveBeenCalledWith('saved-1', 3, expect.any(AbortSignal))
    expect(vi.mocked(getRegimeSearch)).not.toHaveBeenCalled()
    act(() => result.current.showMore())
    await waitFor(() => expect(vi.mocked(getRegimeSearchById)).toHaveBeenLastCalledWith('saved-1', 10, expect.any(AbortSignal)))
  })
})
