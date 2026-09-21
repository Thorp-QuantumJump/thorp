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
  byField: [{ id: 'F1', name: '분야 하나', baselineMonth: '2026-07' }],
  composite: null,
  range: null,
  note: '',
}

function caseOf(endMonth: string, rank: number): RegimeLinkCase {
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
    availability: { state: 'NORMAL' },
    perRun: [],
    shownInFiltered: true,
  }
}

function availabilityOf(cases: RegimeLinkCase[]): RegimeLinkAvailabilityResult {
  return {
    basis: { kind: 'HISTORICAL_REVISION' },
    search: { snapshotKey: 'SNAP', status: 'NEAREST_ONLY' },
    bundle: { runs: [], instruments: [] },
    filter: {
      applied: true,
      filterKey: 'k',
      availableRange: { firstCaseEndMonth: '2006-10', lastCaseEndMonth: '2026-05' },
      excludedCount: 2,
      minPostTradingDays: 21,
    },
    cases,
  }
}

function renderPage(search: string) {
  return render(
    <MemoryRouter initialEntries={[`/regime-link${search}`]}>
      <Routes>
        <Route path="/regime-link" element={<RegimeLinkPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  api.baselines.mockReset().mockResolvedValue(baselines)
  api.availability.mockReset()
  api.createSearch.mockReset()
  api.listLinks.mockReset().mockResolvedValue([])
  api.result.mockReset().mockRejectedValue(new Error('요약은 이 테스트의 대상이 아니다'))
  api.news.mockReset().mockRejectedValue(new Error('뉴스는 이 테스트의 대상이 아니다'))
  api.auth.mockReturnValue({ status: 'authenticated', user: { id: 1 } })
})

describe('비교 묶음 변경 표시', () => {
  it('사례를 열지 않아도 알림과 변경 내용을 보인다', async () => {
    const result = availabilityOf([caseOf('2012-03', 1)])
    api.availability.mockResolvedValue(result)
    api.createSearch.mockResolvedValue({
      ...result,
      linkSearchId: 'RLS-2',
      bundleChanged: { added: ['2012-03'], removed: ['2005-11'], excludedCount: 2 },
    })
    renderPage(`?base=${base}&snapshot=SNAP`)

    expect(await screen.findByText(/비교 묶음이 달라져/)).toBeTruthy()
    expect(screen.getByText('2005-11')).toBeTruthy()
  })

  it('변경 뒤 목록이 0건이어도 기록하고 정상 화면을 보인다', async () => {
    const result = availabilityOf([])
    api.availability.mockResolvedValue(result)
    api.createSearch.mockResolvedValue({
      ...result,
      linkSearchId: 'RLS-3',
      bundleChanged: { added: [], removed: ['2005-11'], excludedCount: 2 },
    })
    renderPage(`?base=${base}&snapshot=SNAP`)

    expect(await screen.findByText(/선택한 ETF로 볼 수 있는 사례가 없습니다/)).toBeTruthy()
    await waitFor(() => expect(api.createSearch).toHaveBeenCalledTimes(1))
  })

  it('같은 묶음이면 알림이 없다', async () => {
    const result = availabilityOf([caseOf('2012-03', 1)])
    api.availability.mockResolvedValue(result)
    api.createSearch.mockResolvedValue({ ...result, linkSearchId: 'RLS-4' })
    renderPage(`?base=${base}&snapshot=SNAP`)

    await screen.findByText('2012-03')
    expect(screen.queryByText(/비교 묶음이 달라져/)).toBeNull()
  })

  it('전체 탐색 전환을 새 요청 id로 기록한다', async () => {
    const result = availabilityOf([caseOf('2012-03', 1)])
    api.availability.mockResolvedValue(result)
    api.createSearch.mockResolvedValue({ ...result, linkSearchId: 'RLS-5' })
    renderPage(`?base=${base}&snapshot=SNAP`)
    await screen.findByText('2012-03')

    await userEvent.click(screen.getByRole('button', { name: '전체 경제환경 탐색으로 전환' }))
    await waitFor(() => expect(api.createSearch).toHaveBeenCalledTimes(2))
    const [first, second] = api.createSearch.mock.calls.map((call) => call[0])
    expect(first.fullSearchShown).toBe(false)
    expect(second.fullSearchShown).toBe(true)
    expect(second.clientRequestId).not.toBe(first.clientRequestId)
  })

  it('익명은 기록을 만들지 않고 직전 목록으로 변경만 표시한다', async () => {
    api.auth.mockReturnValue({ status: 'anonymous', user: null })
    window.sessionStorage.setItem(
      `thorp.regimeLink.exploration.${base}`,
      JSON.stringify({
        snapshotKey: 'SNAP',
        entryPath: 'STRATEGY_RESULT',
        full: false,
        previousCases: ['2005-11'],
      }),
    )
    api.availability.mockResolvedValue(availabilityOf([caseOf('2012-03', 1)]))
    renderPage(`?base=${base}&snapshot=SNAP`)

    expect(await screen.findByText(/비교 묶음이 달라져/)).toBeTruthy()
    expect(api.createSearch).not.toHaveBeenCalled()
  })

  it('기록 생성이 실패해도 목록은 그대로 보인다', async () => {
    api.availability.mockResolvedValue(availabilityOf([caseOf('2012-03', 1)]))
    api.createSearch.mockRejectedValue(new Error('기록 실패'))
    renderPage(`?base=${base}&snapshot=SNAP`)

    expect(await screen.findByText('2012-03')).toBeTruthy()
  })
})
