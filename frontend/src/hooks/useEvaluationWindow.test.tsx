import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AnalysisSupport } from '@/components/strategy/analysisSupport'
import type { EvaluationWindow, EvaluationWindowQuery } from '@/types/evaluationWindow'
import { useEvaluationWindow } from './useEvaluationWindow'

function windowFor(datasetId: string): EvaluationWindow {
  return {
    commonStart: '2024-01-02',
    commonEnd: '2026-01-05',
    warmupTradingDays: 0,
    bindingCandidate: '000001.XKRX',
    bindingSector: 'A',
    excludedRanges: [],
    evaluable: true,
    datasetId,
    warmupBaseDate: '2024-01-02',
    tradingDaysFromBase: ['2024-01-02'],
    maxWarmupTradingDays: 0,
    excludedRangesFromBase: [],
  }
}

type Props = { query: EvaluationWindowQuery | 'uncomputable' | null }

describe('useEvaluationWindow(T042)', () => {
  it('내용이 같은 질의를 새 객체로 넘겨도 다시 조회하지 않는다', async () => {
    const fetch = vi.fn<AnalysisSupport['fetchEvaluationWindow']>(async () => windowFor('D1'))
    const { result, rerender } = renderHook(({ query }: Props) => useEvaluationWindow(fetch, query), {
      initialProps: { query: { sectors: ['A', 'B'], maxWarmupTradingDays: 90 } },
    })

    expect(result.current.state.status).toBe('loading')
    await waitFor(() => expect(result.current.state.status).toBe('loaded'))

    rerender({ query: { sectors: ['A', 'B'], maxWarmupTradingDays: 90 } })
    rerender({ query: { sectors: ['A', 'B'], maxWarmupTradingDays: 90 } })

    expect(fetch).toHaveBeenCalledTimes(1)
    // 판정이 달력이 끝난 것과 요청 밖을 가를 수 있게 보낸 상한을 함께 담는다.
    expect(result.current.state).toMatchObject({ status: 'loaded', requestedMaxWarmup: 90 })
  })

  it('섹터 집합이 바뀌면 다시 조회하고, 늦게 온 옛 응답은 새 집합을 판정하지 않는다', async () => {
    let resolveOld: (window: EvaluationWindow) => void = () => {}
    const fetch = vi.fn<AnalysisSupport['fetchEvaluationWindow']>((query) =>
      query.sectors.includes('C')
        ? Promise.resolve(windowFor('NEW'))
        : new Promise((resolve) => {
            resolveOld = resolve
          }),
    )
    const { result, rerender } = renderHook(({ query }: Props) => useEvaluationWindow(fetch, query), {
      initialProps: { query: { sectors: ['A', 'B'], maxWarmupTradingDays: 90 } },
    })

    rerender({ query: { sectors: ['A', 'C'], maxWarmupTradingDays: 90 } })
    await waitFor(() => expect(result.current.state.status).toBe('loaded'))

    await act(async () => resolveOld(windowFor('OLD')))

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[0][1]?.aborted).toBe(true)
    const state = result.current.state
    expect(state.status === 'loaded' && state.window.datasetId).toBe('NEW')
  })

  it('조회가 실패하면 받지 못한 상태다', async () => {
    const fetch = vi.fn<AnalysisSupport['fetchEvaluationWindow']>(async () => {
      throw new Error('404')
    })
    const { result } = renderHook(() =>
      useEvaluationWindow(fetch, { sectors: ['A', 'B'], maxWarmupTradingDays: 90 }),
    )

    await waitFor(() => expect(result.current.state.status).toBe('unavailable'))
  })

  it('받지 못했으면 다시 시도로 같은 조회를 한 번 더 보내고, 받는 동안 안내 상태를 거두지 않는다', async () => {
    let fail = true
    let release: (window: EvaluationWindow) => void = () => {}
    const fetch = vi.fn<AnalysisSupport['fetchEvaluationWindow']>(() =>
      fail
        ? Promise.reject(new Error('409 EVALUATION_WINDOW_NOT_READY'))
        : new Promise((resolve) => {
            release = resolve
          }),
    )
    const { result } = renderHook(() =>
      useEvaluationWindow(fetch, { sectors: ['A', 'B'], maxWarmupTradingDays: 90 }),
    )
    await waitFor(() => expect(result.current.state.status).toBe('unavailable'))
    expect(result.current.retrying).toBe(false)

    fail = false
    act(() => result.current.retry())

    // 다시 받는 동안에도 받지 못한 상태에 머물러 안내가 깜빡이지 않고, 다시 받는 중임을 알린다.
    await waitFor(() => expect(result.current.retrying).toBe(true))
    expect(result.current.state.status).toBe('unavailable')
    expect(fetch).toHaveBeenCalledTimes(2)

    await act(async () => release(windowFor('RETRIED')))
    const state = result.current.state
    expect(state.status === 'loaded' && state.window.datasetId).toBe('RETRIED')
    expect(result.current.retrying).toBe(false)
  })

  it('상한을 계산하지 못했거나 조건이 갖춰지지 않았으면 조회하지 않는다', () => {
    const fetch = vi.fn<AnalysisSupport['fetchEvaluationWindow']>(async () => windowFor('D1'))
    const { result, rerender } = renderHook(({ query }: Props) => useEvaluationWindow(fetch, query), {
      initialProps: { query: 'uncomputable' } as Props,
    })

    expect(result.current.state.status).toBe('uncomputable')
    rerender({ query: null })
    expect(result.current.state.status).toBe('loading')
    expect(fetch).not.toHaveBeenCalled()
  })
})
