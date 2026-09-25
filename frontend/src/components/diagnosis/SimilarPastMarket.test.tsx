import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeBaselineMonths, getRegimeSearch } from '@/api/regime'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { RegimeBaselineMonths, RegimeSearchResult } from '@/types/regime'
import { SimilarPastMarket } from './SimilarPastMarket'

vi.mock('@/api/regime', () => ({ getRegimeBaselineMonths: vi.fn(), getRegimeSearch: vi.fn() }))

const baselines: RegimeBaselineMonths = {
  byField: [
    { id: 'production', name: '생산', baselineMonth: '2026-07' },
    { id: 'labor', name: '노동', baselineMonth: '2026-08' },
  ],
  composite: '2026-07',
  range: { earliest: '2000-01', latest: '2026-08' },
  note: '',
}

function searchResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'S-1',
    mode: 'field',
    field: { id: 'production', name: '생산' },
    baselineMonth: '2026-07',
    cases: [
      {
        endMonth: '2019-08',
        rank: 1,
        observationMonths: ['2019-06', '2019-07', '2019-08'],
        distance: 0.4,
        similar: ['광공업생산 증가율이 비슷했습니다.'],
        different: ['서비스업생산은 더 낮았습니다.'],
        detailAvailable: true,
      },
    ],
    ...overrides,
  } as RegimeSearchResult
}

describe('SimilarPastMarket (002 FR-018)', () => {
  beforeEach(() => {
    vi.mocked(getRegimeBaselineMonths).mockResolvedValue(baselines)
    vi.mocked(getRegimeSearch).mockResolvedValue(searchResult())
  })

  it('첫 분야의 최신 기준월로 먼저 열고, 종합 비교는 누를 때만 연다', async () => {
    renderWithRouter(<SimilarPastMarket caseId={null} />)

    expect(await screen.findByText('2019-06 ~ 2019-08')).toBeTruthy()
    expect(vi.mocked(getRegimeSearch).mock.calls[0][0]).toEqual({ mode: 'field', field: 'production', limit: 3 })
    expect(screen.getByRole('button', { name: '생산' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: 'Thorp 종합 비교' }).getAttribute('aria-pressed')).toBe('false')

    fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch).mock.calls.at(-1)?.[0]).toEqual({ mode: 'composite', limit: 3 }))
  })

  it('사례를 고르면 011 진입 계약으로 그 기간을 분석 기간으로 이어 간다', async () => {
    renderWithRouter(<SimilarPastMarket caseId={null} />)

    const link = await screen.findByRole('link', { name: '이 기간으로 백테스트' })
    expect(link.getAttribute('href')).toBe('/regime-link/start?case=S-1%7E2019-08')
  })

  it('고른 사례는 고른 기간으로 보이고 해제할 수 있다', async () => {
    renderWithRouter(<SimilarPastMarket caseId="S-1~2019-08" />)

    expect(await screen.findByText(/고른 기간/)).toBeTruthy()
    expect(screen.queryByRole('link', { name: '이 기간으로 백테스트' })).toBeNull()
    expect(screen.getByRole('link', { name: '사례 해제' }).getAttribute('href')).toBe('/diagnosis/sectors')
  })

  it('사례 해제를 누르면 사례 없는 투자 대상 고르기로 돌아가 다시 고를 수 있다', async () => {
    // 화면은 고른 사례를 주소의 case에서만 읽는다(SectorSelectionPage와 같은 방식).
    function FromAddress() {
      const [params] = useSearchParams()
      return <SimilarPastMarket caseId={params.get('case')} />
    }
    render(
      <MemoryRouter initialEntries={['/diagnosis/sectors?case=S-1~2019-08']}>
        <Routes>
          <Route path="/diagnosis/sectors" element={<FromAddress />} />
        </Routes>
      </MemoryRouter>,
    )

    fireEvent.click(await screen.findByRole('link', { name: '사례 해제' }))

    expect(await screen.findByRole('link', { name: '이 기간으로 백테스트' })).toBeTruthy()
    expect(screen.queryByText(/고른 기간/)).toBeNull()
  })

  it('받지 못해도 경고로 막지 않고 다시 시도를 둔다', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValue(new Error('offline'))
    renderWithRouter(<SimilarPastMarket caseId={null} />)

    expect(await screen.findByText('과거 사례를 불러오지 못했습니다.')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()
  })

  it('화면이 순위 번호나 추천 표현을 만들지 않는다', async () => {
    const { container } = renderWithRouter(<SimilarPastMarket caseId={null} />)
    await screen.findByText('2019-06 ~ 2019-08')

    const text = container.textContent ?? ''
    for (const forbidden of ['추천', '1위', '최적']) expect(text).not.toContain(forbidden)
  })

  it('종합 비교에 기준 충족 사례가 없으면 안내와 분야별 탐색·참고 사례 보기를 보인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(
      searchResult({
        mode: 'COMPOSITE',
        field: null,
        status: 'THRESHOLD_APPLIED',
        cases: [],
        thresholdNote: '설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.',
      }),
    )
    renderWithRouter(<SimilarPastMarket caseId={null} />)
    expect(await screen.findByText(/설정된 종합 유사성 기준을 충족하는 사례가 없습니다/)).toBeTruthy()
    expect(screen.getByRole('link', { name: /참고 사례 보기/ }).getAttribute('href')).toContain('searchId=S-1')
    fireEvent.click(screen.getByRole('button', { name: '분야별로 살펴보기' }))
    await waitFor(() =>
      expect(vi.mocked(getRegimeSearch).mock.calls.at(-1)?.[0]).toEqual({ mode: 'field', field: 'production', limit: 3 }),
    )
  })
})
