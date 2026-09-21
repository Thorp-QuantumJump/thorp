import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resultDetailCompleted } from '@/test/resultFixtures'
import type { PublicResultDetail } from '@/types/result'
import type { RegimeLinkAvailabilityResult, RegimeLinkCase, RegimeLinkView } from '@/types/regimeLink'
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
const caseId = 'FIELD:LABOR:2026-07-01:MACRO:REG:REGIME-V1~2012-03'

const caseItem: RegimeLinkCase = {
  caseId,
  endMonth: '2012-03',
  rank: 1,
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

const availability: RegimeLinkAvailabilityResult = {
  basis: { kind: 'HISTORICAL_REVISION' },
  search: { snapshotKey: 'FIELD:LABOR:2026-07-01:MACRO:REG:REGIME-V1', status: 'NEAREST_ONLY' },
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
  // 시장 흐름 경로는 필터를 걸지 않는다.
  filter: { applied: false, minPostTradingDays: 21 },
  cases: [caseItem],
}

const link: RegimeLinkView = {
  basis: { kind: 'HISTORICAL_REVISION' },
  linkId: 'RL-1',
  publicToken: base,
  caseId,
  scenario: 'CONTINUOUS',
  status: 'COMPLETED',
  postWindow: caseItem.postWindow,
  availability: { state: 'NORMAL' },
  runProgress: null,
  failure: null,
  result: {
    window63: {
      tradingDays: 63,
      return: '0.0421',
      maxDrawdown: '-0.0587',
      tradeCount: 6,
      tradesPerMonth: '2.00',
      turnover: '0.3125',
      turnoverBasis: 'ANNUALIZED_252',
      costs: { commission: '0', spread: '0', slippage: '0', tax: '0', total: '1523.00' },
      lossContribution: {},
      complete: true,
    },
    window21: null,
    holdings: [],
    trades: [],
    kospi: {},
  },
}

function running(): PublicResultDetail {
  return {
    ...resultDetailCompleted,
    publicToken: base,
    status: 'RUNNING',
    progress: {
      stage: 'COMPUTING',
      lastTradingDate: '2020-01-02',
      completedTradingDays: 120,
      totalTradingDays: 400,
    },
    queueWaitSeconds: 8,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/regime-link?base=${base}&case=${encodeURIComponent(caseId)}`]}>
      <Routes>
        <Route path="/regime-link" element={<RegimeLinkPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  api.baselines.mockReset().mockResolvedValue({ byField: [], composite: null, range: null, note: '' })
  api.availability.mockReset().mockResolvedValue(availability)
  api.createSearch.mockReset().mockResolvedValue({ ...availability, linkSearchId: 'RLS-1' })
  api.createLinks.mockReset().mockResolvedValue({
    basis: { kind: 'HISTORICAL_REVISION' },
    linkSearchId: 'RLS-1',
    links: [{ linkId: 'RL-1', publicToken: base, scenario: 'CONTINUOUS', status: 'COMPLETED' }],
  })
  api.listLinks.mockReset().mockResolvedValue([link])
  api.result.mockReset().mockResolvedValue({ ...resultDetailCompleted, publicToken: base })
  api.news.mockReset().mockRejectedValue(new Error('뉴스는 이 테스트의 대상이 아니다'))
  api.auth.mockReturnValue({ status: 'authenticated', user: { id: 1 } })
})

describe('시장 흐름 경로의 011 결과 화면', () => {
  it('기준 실행이 끝나지 않았으면 진행 상태를 보이고 목록을 부르지 않는다', async () => {
    api.result.mockResolvedValue(running())
    renderPage()

    expect(await screen.findByText('분석을 계산하는 중입니다')).toBeTruthy()
    expect(screen.getByText('COMPUTING')).toBeTruthy()
    expect(api.availability).not.toHaveBeenCalled()
  })

  it('주소의 사례를 유지하고 필터 없이 판정만 붙은 목록을 부른다', async () => {
    renderPage()

    await waitFor(() => expect(api.availability).toHaveBeenCalled())
    expect(api.availability).toHaveBeenLastCalledWith(
      expect.objectContaining({
        base,
        entryPath: 'MARKET_FLOW',
        snapshotKey: 'FIELD:LABOR:2026-07-01:MACRO:REG:REGIME-V1',
      }),
    )
    expect(screen.queryByText('경제 분야를 고르세요')).toBeNull()
  })

  it('사례가 스스로 열려 결과 영역과 전체 기간 요약이 함께 보인다', async () => {
    renderPage()

    expect(await screen.findByText('2012-03 사례의 전략 결과')).toBeTruthy()
    expect(await screen.findByText('0.0421')).toBeTruthy()
    expect(screen.getByText('전체 기간 결과')).toBeTruthy()
    await waitFor(() => expect(api.createLinks).toHaveBeenCalledTimes(1))
  })

  it('익명은 계산을 만들지 않고 목록만 본다', async () => {
    api.auth.mockReturnValue({ status: 'anonymous', user: null })
    renderPage()

    await waitFor(() => expect(api.availability).toHaveBeenCalled())
    expect(api.createSearch).not.toHaveBeenCalled()
    expect(api.createLinks).not.toHaveBeenCalled()
  })

  it('익명도 주소의 사례에 이미 있는 공개 결과는 읽는다', async () => {
    api.auth.mockReturnValue({ status: 'anonymous', user: null })
    renderPage()

    expect(await screen.findByText('2012-03 사례의 전략 결과')).toBeTruthy()
    expect(await screen.findByText('0.0421')).toBeTruthy()
    expect(api.listLinks).toHaveBeenCalledWith(expect.objectContaining({ base, caseId }))
    expect(api.createLinks).not.toHaveBeenCalled()
  })

  it('기준 실행이 실패로 끝나면 그 사실과 005 결과 주소를 안내한다', async () => {
    api.result.mockResolvedValue({ ...resultDetailCompleted, publicToken: base, status: 'FAILED' })
    renderPage()

    expect(
      await screen.findByText('이 분석은 끝나지 않았습니다. 결과 화면에서 상태를 확인해 주세요.'),
    ).toBeTruthy()
    expect(screen.getByRole('link', { name: '전체 기간 결과 보기' }).getAttribute('href')).toBe(
      `/results/${base}`,
    )
    expect(api.availability).not.toHaveBeenCalled()
  })
})
