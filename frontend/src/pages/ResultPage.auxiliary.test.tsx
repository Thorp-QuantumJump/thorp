import { StrictMode } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthContext } from '@/auth/auth-context'
import { anonymousAuthContext } from '@/test/authFixtures'
import {
  resultDetailCompleted,
  resultExceptions,
  resultSectorCatalog,
  resultTimeseries,
  resultToken,
} from '@/test/resultFixtures'
import { registryFixture } from '@/test/strategyRegistryFixtures'
import type { PublicResultDetail } from '@/types/result'
import { ResultPage } from './ResultPage'

const targetToken = 'b41e0c9a7d2f35618ce4a09b7f1d2c63'
const api = vi.hoisted(() => ({
  detail: vi.fn(),
  series: vi.fn(),
  index: vi.fn(),
  registry: vi.fn(),
  create: vi.fn(),
  exceptions: vi.fn(),
  catalog: vi.fn(),
}))

vi.mock('@/api/result', async (load) => ({
  ...(await load<typeof import('@/api/result')>()),
  fetchResult: api.detail,
  fetchResultTimeseries: api.series,
  fetchResultIndexComparison: api.index,
  fetchResultExceptions: api.exceptions,
}))
vi.mock('@/api/sectors', async (load) => ({
  ...(await load<typeof import('@/api/sectors')>()),
  fetchSectorCatalog: api.catalog,
}))
vi.mock('@/api/strategyRegistry', async (load) => ({
  ...(await load<typeof import('@/api/strategyRegistry')>()),
  fetchStrategyRegistry: api.registry,
}))
vi.mock('@/api/backtest', async (load) => ({
  ...(await load<typeof import('@/api/backtest')>()),
  createBacktest: api.create,
}))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="현재 주소">{location.pathname + location.search}</output>
}

function ResultRoute() {
  return (
    <MemoryRouter initialEntries={[`/results/${resultToken}`]}>
      <AuthContext.Provider value={anonymousAuthContext}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
      </Routes>
      <LocationProbe />
    </AuthContext.Provider>
    </MemoryRouter>
  )
}

function renderResult({ strict = false }: { strict?: boolean } = {}) {
  const route = <ResultRoute />
  return render(strict ? <StrictMode>{route}</StrictMode> : route)
}

function targetResult(overrides: Partial<PublicResultDetail> = {}): PublicResultDetail {
  return {
    ...resultDetailCompleted,
    publicToken: targetToken,
    config: {
      ...resultDetailCompleted.config,
      scenario: 'TARGET_RISK',
      scenarioParams: { targetVolatility: '0.1200' },
    },
    summary: resultDetailCompleted.summary && {
      ...resultDetailCompleted.summary,
      netTotalReturn: '0.123456',
      maxDrawdown: '-0.032100',
      totalTradingCost: '23456.78',
    },
    ...overrides,
  }
}

describe('결과 보조 보기 (B08)', () => {
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset())
    api.detail.mockImplementation(async (token: string) =>
      token === resultToken ? resultDetailCompleted : targetResult(),
    )
    api.series.mockResolvedValue(resultTimeseries)
    api.registry.mockResolvedValue(registryFixture)
    api.create.mockResolvedValue({ publicToken: targetToken, status: 'QUEUED' })
    // 탭 묶음이 첫 화면에서 섹터 목록을 받고, 결과 분석 탭을 고르면 예외 기록을 받는다(T098)
    api.exceptions.mockResolvedValue(resultExceptions)
    api.catalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  it('KOSPI 비교 탭을 고르기 전에는 그 API를 부르지 않고 다음 탐색 영역도 없다', async () => {
    renderResult()

    await screen.findByText('자료 출처: 한국거래소 통계정보')
    expect(api.index).not.toHaveBeenCalled()
    expect(api.registry).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()

    expect(screen.queryByRole('heading', { name: '다음 탐색' })).toBeNull()
    expect(screen.queryByRole('button', { name: '기록으로 내보내기' })).toBeNull()
  })

  it('스펙에 없는 이전 화면 행동을 결과 화면에 추가하지 않는다', async () => {
    renderResult()

    await screen.findByText('자료 출처: 한국거래소 통계정보')

    expect(screen.queryByRole('button', { name: '이전 화면으로' })).toBeNull()
  })

  it('KOSPI 비교 탭은 서버 값을 바꾸지 않고 그대로 보이며 주소를 유지한다', async () => {
    api.index.mockResolvedValue({
      available: true,
      reason: null,
      series: [
        { date: '2023-07-03', value: '10000000.00' },
        { date: '2023-07-04', value: '10012345.67' },
      ],
    })
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: 'KOSPI 비교' }))

    const panel = await screen.findByRole('region', { name: '지수와 비교하기' })
    expect(within(panel).getByText('10012345.67')).toBeTruthy()
    expect(within(panel).getByText('지수를 시작 금액으로 환산했습니다.')).toBeTruthy()
    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}`)
  })

  it('지수 기간 계열을 받을 수 없으면 표 대신 서버 이유에 맞는 안내를 보이고 다시 부르지 않는다', async () => {
    api.index.mockResolvedValue({
      available: false,
      reason: 'INDEX_SERIES_UNAVAILABLE',
      series: [],
    })
    const user = userEvent.setup()
    renderResult()

    const tab = await screen.findByRole('tab', { name: 'KOSPI 비교' })
    await user.click(tab)

    const panel = await screen.findByRole('region', { name: '지수와 비교하기' })
    expect(await within(panel).findByText(/같은 기간의 KOSPI 자료를 아직 받을 수 없어/)).toBeTruthy()
    expect(within(panel).queryByRole('table')).toBeNull()
    // 다른 탭으로 옮겼다 돌아와도 같은 요청을 반복하지 않는다.
    await user.click(screen.getByRole('tab', { name: '결과 분석' }))
    await user.click(tab)
    expect(api.index).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}`)
  })
})
