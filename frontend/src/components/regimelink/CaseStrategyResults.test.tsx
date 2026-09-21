import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  RegimeLinkCase,
  RegimeLinkRun,
  RegimeLinkView,
  RegimeLinkWindowResult,
} from '@/types/regimeLink'
import { CaseStrategyResults } from './CaseStrategyResults'

const api = vi.hoisted(() => ({ result: vi.fn(), news: vi.fn() }))

vi.mock('@/api/result', async (load) => ({
  ...(await load<typeof import('@/api/result')>()),
  fetchResult: api.result,
}))
vi.mock('@/api/marketNews', async (load) => ({
  ...(await load<typeof import('@/api/marketNews')>()),
  fetchMarketNewsDigests: api.news,
}))

const baseToken = 'a'.repeat(32)
const compareToken = 'b'.repeat(32)

function windowResult(overrides: Partial<RegimeLinkWindowResult> = {}): RegimeLinkWindowResult {
  return {
    tradingDays: 63,
    return: '0.0421',
    maxDrawdown: '-0.0587',
    tradeCount: 6,
    tradesPerMonth: '2.00',
    turnover: '0.3125',
    turnoverBasis: 'ANNUALIZED_252',
    costs: { commission: '0', spread: '0', slippage: '1523.00', tax: '0', total: '1523.00' },
    lossContribution: { SEMICONDUCTOR: '1.2' },
    complete: true,
    ...overrides,
  }
}

function link(token: string, overrides: Partial<RegimeLinkView> = {}): RegimeLinkView {
  return {
    basis: { kind: 'HISTORICAL_REVISION' },
    linkId: `RL-${token.slice(0, 3)}`,
    publicToken: token,
    caseId: 'SNAP~2012-03',
    scenario: 'CONTINUOUS',
    status: 'COMPLETED',
    postWindow: {
      d0: '2012-04-02',
      d21: '2012-05-02',
      d63: '2012-07-03',
      completedTradingDays: 63,
      complete21: true,
      complete63: true,
    },
    availability: { state: 'NORMAL' },
    runProgress: null,
    failure: null,
    result: {
      window63: windowResult(),
      window21: windowResult({ tradingDays: 21, return: '0.0110' }),
      holdings: [{ date: '2012-04-02', actual: { '091160.XKRX': '0.3980' } }],
      trades: [],
      kospi: { window63: { return: '0.0180', maxDrawdown: '-0.0420' } },
    },
    ...overrides,
  }
}

const runs: RegimeLinkRun[] = [
  {
    publicToken: baseToken,
    role: 'BASE',
    strategyId: 'FIXED_WEIGHT',
    strategyName: '기준 전략',
    period: { start: '2010-01-04', end: '2025-12-30' },
    warmupTradingDays: 63,
  },
  {
    publicToken: compareToken,
    role: 'COMPARE',
    strategyId: 'INVERSE_VOLATILITY',
    strategyName: '비교 전략',
    period: { start: '2010-01-04', end: '2025-12-30' },
    warmupTradingDays: 63,
  },
]

const caseItem: RegimeLinkCase = {
  caseId: 'SNAP~2012-03',
  endMonth: '2012-03',
  rank: 1,
  distance: '0.41',
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

function renderResults(overrides: Parameters<typeof CaseStrategyResults>[0] | null = null) {
  const props = overrides ?? {
    basis: { kind: 'HISTORICAL_REVISION' as const },
    caseItem,
    runs,
    links: [link(baseToken), link(compareToken)],
    freshLinks: [],
    onRetry: vi.fn(),
    onEnableFreshCash: vi.fn(),
  }
  return render(
    <MemoryRouter>
      <CaseStrategyResults {...props} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.result.mockRejectedValue(new Error('전체 기간 요약은 이 테스트의 대상이 아니다'))
  api.news.mockRejectedValue(new Error('뉴스는 이 테스트의 대상이 아니다'))
})

describe('사례의 전략 결과 영역', () => {
  it('같은 평가 날짜와 고정 문구를 머리에 보인다', () => {
    renderResults()
    expect(screen.getByText('최신 개정자료를 이용한 역사 탐색')).toBeTruthy()
    expect(
      screen.getByText('역사 설명용 기준 — 실제 발표 직후 매매를 재현한 것이 아닙니다.'),
    ).toBeTruthy()
    expect(screen.getByText('2012-04-02 ~ 2012-07-03')).toBeTruthy()
  })

  it('005 순서(기준 → 비교 대상)를 유지한다', () => {
    const { container } = renderResults()
    const roles = [...container.querySelectorAll('[data-run-role]')].map((node) =>
      node.getAttribute('data-run-role'),
    )
    expect(roles).toEqual(['BASE', 'COMPARE'])
  })

  it('63거래일이 기본이고 21거래일은 펼쳐야 보인다', () => {
    renderResults()
    expect(screen.getAllByText('0.0421').length).toBe(2)
    expect(screen.queryByText('21거래일 보조 평가')).toBeNull()
    expect(screen.getAllByRole('button', { name: '구간 상세 보기' })).toHaveLength(2)
  })

  it('근거 표기가 없으면 영역을 그리지 않는다', () => {
    const { container } = renderResults({
      basis: null,
      caseItem,
      runs,
      links: [],
      freshLinks: [],
      onRetry: vi.fn(),
      onEnableFreshCash: vi.fn(),
    })
    expect(container.innerHTML).toBe('')
  })
})
