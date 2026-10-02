import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeCaseDetail, getRegimeSearch } from '@/api/regime'
import type { RegimeCaseDetail, RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'
import { useCaseDetail, usePastSearch } from './usePastCases'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))

function result(field: string): RegimeSearchResult {
  return { searchId: `S-${field}`, mode: 'FIELD', field: { id: field, name: field }, cases: [] } as unknown as RegimeSearchResult
}

function detail(searchId: string, endMonth: string): RegimeCaseDetail {
  return { searchId, endMonth, indicators: [] } as unknown as RegimeCaseDetail
}

/** 테스트가 정한 때에 끝나는 응답. 늦게 온 응답이 지금 선택을 덮지 않는지 본다. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('usePastSearch', () => {
  // 화살표가 mockReset()의 반환값(목 자신)을 돌려주면 vitest가 그것을 정리 함수로 불러 버린다. 블록으로 쓴다.
  beforeEach(() => {
    vi.mocked(getRegimeSearch).mockReset()
  })

  it('질의가 바뀐 첫 렌더에도 이전 질의의 결과를 돌려주지 않는다', async () => {
    vi.mocked(getRegimeSearch).mockImplementation(async (query) => result(query.field ?? ''))
    const { result: hook, rerender } = renderHook(({ query }: { query: RegimeSearchQuery }) => usePastSearch(query), {
      initialProps: { query: { mode: 'field', field: 'production', limit: 3 } },
    })
    await waitFor(() => expect(hook.current.state.status).toBe('ready'))

    rerender({ query: { mode: 'field', field: 'labor', limit: 3 } })
    // 효과가 돌기 전 렌더다. 생산 결과가 남아 있어도 받는 중으로 본다.
    expect(hook.current.state.status).toBe('loading')
    await waitFor(() => expect(hook.current.state).toEqual({ status: 'ready', result: result('labor') }))
  })

  it('값이 같은 질의는 객체가 새로 만들어져도 다시 조회하지 않는다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(result('production'))
    const { rerender } = renderHook(({ query }: { query: RegimeSearchQuery }) => usePastSearch(query), {
      initialProps: { query: { mode: 'field', field: 'production', limit: 3 } },
    })
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledTimes(1))

    rerender({ query: { mode: 'field', field: 'production', limit: 3 } })
    expect(getRegimeSearch).toHaveBeenCalledTimes(1)
  })
})

describe('useCaseDetail', () => {
  beforeEach(() => {
    vi.mocked(getRegimeCaseDetail).mockReset()
  })

  it('사례를 빨리 바꾸면 늦게 온 이전 사례의 응답을 버린다', async () => {
    const first = deferred<RegimeCaseDetail>()
    vi.mocked(getRegimeCaseDetail).mockImplementation((endMonth) =>
      endMonth === '2001-03' ? first.promise : Promise.resolve(detail('S-1', '2011-10')))
    const { result: hook, rerender } = renderHook(({ endMonth }: { endMonth: string | null }) => useCaseDetail('S-1', endMonth), {
      initialProps: { endMonth: '2001-03' as string | null },
    })

    rerender({ endMonth: '2011-10' })
    await waitFor(() => expect(hook.current.state).toEqual({ status: 'ready', detail: detail('S-1', '2011-10') }))
    await act(async () => first.resolve(detail('S-1', '2001-03')))
    expect(hook.current.state).toEqual({ status: 'ready', detail: detail('S-1', '2011-10') })
  })

  it('받은 사례로 돌아오면 다시 받지 않고, 지금만이어도 그 검색의 척도 원천을 남긴다', async () => {
    vi.mocked(getRegimeCaseDetail).mockImplementation(async (endMonth) => detail('S-1', endMonth))
    const { result: hook, rerender } = renderHook(({ endMonth }: { endMonth: string | null }) => useCaseDetail('S-1', endMonth), {
      initialProps: { endMonth: '2001-03' as string | null },
    })
    await waitFor(() => expect(hook.current.state?.status).toBe('ready'))
    rerender({ endMonth: '2011-10' })
    await waitFor(() => expect(hook.current.state).toEqual({ status: 'ready', detail: detail('S-1', '2011-10') }))

    rerender({ endMonth: '2001-03' })
    await waitFor(() => expect(hook.current.state).toEqual({ status: 'ready', detail: detail('S-1', '2001-03') }))
    expect(getRegimeCaseDetail).toHaveBeenCalledTimes(2)

    rerender({ endMonth: null })
    expect(hook.current.state).toBeNull()
    expect(hook.current.scaleSource?.searchId).toBe('S-1')
  })

  it('요청한 사례와 다른 응답은 섞지 않는다 (010 FR-029)', async () => {
    vi.mocked(getRegimeCaseDetail).mockResolvedValue(detail('S-OTHER', '2001-03'))
    const { result: hook } = renderHook(() => useCaseDetail('S-1', '2001-03'))

    await waitFor(() => expect(hook.current.state).toEqual({ status: 'mismatch' }))
    expect(hook.current.scaleSource).toBeNull()
  })
})
