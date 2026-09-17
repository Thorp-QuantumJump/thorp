import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeBaselineMonths, getRegimeSearch } from '@/api/regime'
import { regimeResult } from '@/test/regimeFixtures'
import { RegimeSearchPage } from './RegimeSearchPage'

vi.mock('@/api/regime', () => ({ getRegimeBaselineMonths: vi.fn(), getRegimeSearch: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => null }))

const baseline = {
  byField: [{ id: 'field-1', name: '시험 분야', baselineMonth: '2026-07' }],
  composite: '2026-05', range: { earliest: '1995-04', latest: '2026-07' }, note: '전환 안내',
}

async function search() {
  render(<RegimeSearchPage />)
  fireEvent.change(await screen.findByLabelText('경제 분야'), { target: { value: 'field-1' } })
  fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
}

describe('경제환경 검색 화면', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getRegimeBaselineMonths).mockResolvedValue(baseline)
  })

  it('API 분야 목록으로 검색하고 다섯 표시와 자료 기준 시점을 보인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({
      comparisonMonths: [{ indicatorId: 'indicator-1', indicator: '시험 지표', months: ['2026-05'], omitted: ['2026-06'], note: '시험 지표는 2026년 6월 자료가 없어 그 달을 빼고 비교했습니다.' }],
      counts: { evaluated: 30, eligible: 20, stored: 10, displayed: 1 },
      displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    }))
    await search()
    expect(await screen.findByText('2012-03')).toBeTruthy()
    expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledWith(
      { mode: 'field', field: 'field-1', baselineMonth: undefined, limit: 3 },
      expect.any(AbortSignal),
    )
    for (const label of ['검색 상태', '사례 구분', '예시 자료', '빠진 비교 시점', '역사 비교']) {
      expect(screen.getByRole('status', { name: `시험 분야 2026-07 검색 결과: ${label}` })).toBeTruthy()
    }
    expect(screen.getByText(/대상월 2026-07 · 발표일 2026-09-12/)).toBeTruthy()
    expect(screen.queryByText('30')).toBeNull()
    expect(screen.queryByText('20')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'field', field: 'field-1', baselineMonth: undefined, limit: 10 },
      expect.any(AbortSignal),
    ))
  })

  it('계산 불가를 정상 결과로 보여 주고 조회 실패에는 재시도를 제공한다', async () => {
    vi.mocked(getRegimeSearch)
      .mockResolvedValueOnce(regimeResult({
        status: 'NOT_COMPUTABLE', statusReason: 'NO_CANDIDATE',
        statusNote: '비교할 수 있는 과거 시점이 없습니다.', cases: [],
        counts: { evaluated: 0, eligible: 0, stored: 0, displayed: 0 },
      }))
      .mockRejectedValueOnce(new Error('조회 실패'))
    await search()
    expect(await screen.findByRole('heading', { name: '비교할 수 있는 과거 시점이 없습니다.' })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '비교할 수 있는 과거 시점이 없습니다.' })).toBeNull()
  })
})
