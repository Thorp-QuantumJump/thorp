import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RegimeBaselineMonths } from '@/types/regime'
import type { RegimeLinkAvailabilityResult, RegimeLinkCase } from '@/types/regimeLink'
import { RegimeLinkPage } from './RegimeLinkPage'

const api = vi.hoisted(() => ({
  baselines: vi.fn(),
  availability: vi.fn(),
  createSearch: vi.fn(),
  createLinks: vi.fn(),
  listLinks: vi.fn(),
  result: vi.fn(),
  news: vi.fn(),
  auth: vi.fn(),
}))

vi.mock('@/api/regime', async (load) => ({
  ...(await load<typeof import('@/api/regime')>()),
  getRegimeBaselineMonths: api.baselines,
}))
vi.mock('@/api/regimeLink', async (load) => ({
  ...(await load<typeof import('@/api/regimeLink')>()),
  fetchAvailability: api.availability,
  createRegimeLinkSearch: api.createSearch,
  createRegimeLinks: api.createLinks,
  listRegimeLinks: api.listLinks,
}))
vi.mock('@/api/result', async (load) => ({
  ...(await load<typeof import('@/api/result')>()),
  fetchResult: api.result,
}))
vi.mock('@/api/marketNews', async (load) => ({
  ...(await load<typeof import('@/api/marketNews')>()),
  fetchMarketNewsDigests: api.news,
}))
vi.mock('@/auth/auth-context', async (load) => ({
  ...(await load<typeof import('@/auth/auth-context')>()),
  useAuth: () => api.auth(),
}))

const base = 'a'.repeat(32)

const baselines: RegimeBaselineMonths = {
  byField: [
    { id: 'F1', name: '분야 하나', baselineMonth: '2026-07' },
    { id: 'F2', name: '분야 둘', baselineMonth: '2026-07' },
  ],
  composite: '2026-07',
  range: { earliest: '2000-01', latest: '2026-07' },
  note: '',
}

function caseOf(endMonth: string, rank: number, shown: boolean, state: RegimeLinkCase['availability']['state'] = 'NORMAL'): RegimeLinkCase {
  return {
    caseId: `SNAP~${endMonth}`,
    endMonth,
    rank,
    distance: '0.4',
    postWindow: {
      d0: '2012-04-02',
      d21: '2012-05-02',
      d63: '2012-07-03',
      completedTradingDays: 63,
      complete21: true,
      complete63: true,
    },
    availability: { state },
    perRun: [],
    shownInFiltered: shown,
  }
}

function availabilityOf(cases: RegimeLinkCase[]): RegimeLinkAvailabilityResult {
  return {
    basis: { kind: 'HISTORICAL_REVISION' },
    search: { snapshotKey: 'SNAP', mode: 'field', field: 'F1', baselineMonth: '2026-07', status: 'NEAREST_ONLY' },
    bundle: {
      runs: [
        {
          publicToken: base,
          role: 'BASE',
          strategyId: 'FIXED_WEIGHT',
          strategyName: '기준 전략',
          period: { start: '2010-01-04', end: '2025-12-30' },
          warmupTradingDays: 63,
        },
      ],
      instruments: [],
    },
    filter: {
      applied: true,
      filterKey: 'k',
      availableRange: { firstCaseEndMonth: '2006-10', lastCaseEndMonth: '2026-05' },
      excludedCount: 3,
      minPostTradingDays: 21,
    },
    cases,
  }
}

function renderPage(search = `?base=${base}`) {
  return render(
    <MemoryRouter initialEntries={[`/regime-link${search}`]}>
      <Routes>
        <Route path="/regime-link" element={<RegimeLinkPage />} />
        <Route path="/diagnosis/sectors" element={<p>진단 시작</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  api.baselines.mockReset().mockResolvedValue(baselines)
  api.availability.mockReset()
  api.createSearch.mockReset()
  api.createLinks.mockReset()
  api.listLinks.mockReset().mockResolvedValue([])
  api.result.mockReset().mockRejectedValue(new Error('요약은 이 테스트의 대상이 아니다'))
  api.news.mockReset().mockRejectedValue(new Error('뉴스는 이 테스트의 대상이 아니다'))
  api.auth.mockReturnValue({ status: 'anonymous', user: null })
})

describe('진단 결과 경로의 011 결과 화면', () => {
  it('분야 선택이 먼저이고 고르기 전에는 목록을 부르지 않는다', async () => {
    renderPage()
    expect(await screen.findByText('경제 분야를 고르세요')).toBeTruthy()
    expect(api.availability).not.toHaveBeenCalled()
  })

  it('종합 비교는 기본이 아니다', async () => {
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    const composite = screen.getByRole('button', { name: 'Thorp 종합 비교' })
    expect(composite.getAttribute('aria-pressed')).toBe('false')
  })

  it('기본 목록에는 shownInFiltered가 참인 사례만 그리고 순위를 응답 그대로 보인다', async () => {
    api.availability.mockResolvedValue(
      availabilityOf([caseOf('2012-03', 1, true), caseOf('2005-11', 2, false, 'DATA_INSUFFICIENT')]),
    )
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    await userEvent.click(screen.getByRole('button', { name: '분야 하나' }))

    expect(await screen.findByText('2012-03')).toBeTruthy()
    expect(screen.queryByText('2005-11')).toBeNull()
    expect(screen.getByText('1')).toBeTruthy()
  })

  it('배너에 비교 가능한 기간과 제외 수를 보인다', async () => {
    api.availability.mockResolvedValue(availabilityOf([caseOf('2012-03', 1, true)]))
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    await userEvent.click(screen.getByRole('button', { name: '분야 하나' }))

    expect(await screen.findByText(/선택 ETF로 비교 가능한 기간 2006-10 ~ 2026-05/)).toBeTruthy()
    expect(screen.getByText('제외된 사례 3개')).toBeTruthy()
  })

  it('전체 탐색으로 전환하면 필터 전 사례가 판정과 함께 보인다', async () => {
    api.availability.mockResolvedValue(
      availabilityOf([caseOf('2012-03', 1, true), caseOf('2005-11', 2, false, 'DATA_INSUFFICIENT')]),
    )
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    await userEvent.click(screen.getByRole('button', { name: '분야 하나' }))
    await screen.findByText('2012-03')

    await userEvent.click(screen.getByRole('button', { name: '전체 경제환경 탐색으로 전환' }))
    expect(await screen.findByText('2005-11')).toBeTruthy()
    expect(screen.getByText('DATA_INSUFFICIENT')).toBeTruthy()
  })

  it('필터 뒤 0건은 정상 화면이고 전환 버튼이 있다', async () => {
    api.availability.mockResolvedValue(availabilityOf([caseOf('2005-11', 1, false)]))
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    await userEvent.click(screen.getByRole('button', { name: '분야 하나' }))

    expect(await screen.findByText(/선택한 ETF로 볼 수 있는 사례가 없습니다/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '전체 경제환경 탐색으로 전환' })).toBeTruthy()
  })

  it('base가 없으면 빈 화면 대신 진단 시작으로 안내한다', async () => {
    renderPage('')
    expect(await screen.findByText(/진단을 처음부터 시작해 주세요/)).toBeTruthy()
    expect(screen.getByRole('link', { name: '진단 시작하기' })).toBeTruthy()
  })

  it('base 형식이 틀려도 같은 안내를 보인다', async () => {
    renderPage('?base=zzz')
    expect(await screen.findByText(/진단을 처음부터 시작해 주세요/)).toBeTruthy()
  })

  it('로그인 사용자는 검색 기록을 만들고 사례를 열 수 있다', async () => {
    api.auth.mockReturnValue({ status: 'authenticated', user: { id: 1 } })
    const result = availabilityOf([caseOf('2012-03', 1, true)])
    api.availability.mockResolvedValue(result)
    api.createSearch.mockResolvedValue({ ...result, linkSearchId: 'RLS-1' })
    api.createLinks.mockResolvedValue({
      basis: { kind: 'HISTORICAL_REVISION' },
      linkSearchId: 'RLS-1',
      links: [{ linkId: 'RL-1', publicToken: base, scenario: 'CONTINUOUS', status: 'QUEUED' }],
    })
    renderPage()
    await screen.findByText('경제 분야를 고르세요')
    await userEvent.click(screen.getByRole('button', { name: '분야 하나' }))
    await screen.findByText('2012-03')

    await userEvent.click(screen.getByRole('button', { name: '전략 결과 보기' }))
    await waitFor(() =>
      expect(api.createLinks).toHaveBeenCalledWith({
        linkSearchId: 'RLS-1',
        caseId: 'SNAP~2012-03',
        scenario: 'CONTINUOUS',
        confirmFullPeriod: false,
      }),
    )
  })
})
