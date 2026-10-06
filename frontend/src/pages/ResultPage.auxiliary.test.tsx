import { render, screen } from '@testing-library/react'
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
import { ResultPage } from './ResultPage'

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

function ResultRoute({ search = '' }: { search?: string }) {
  return (
    <MemoryRouter initialEntries={[`/results/${resultToken}${search}`]}>
      <AuthContext.Provider value={anonymousAuthContext}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
      </Routes>
      <LocationProbe />
    </AuthContext.Provider>
    </MemoryRouter>
  )
}

function renderResult({ search }: { search?: string } = {}) {
  return render(<ResultRoute search={search} />)
}

describe('결과 보조 보기 (B08)', () => {
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset())
    api.detail.mockResolvedValue(resultDetailCompleted)
    api.series.mockResolvedValue(resultTimeseries)
    api.registry.mockResolvedValue(registryFixture)
    // 탭 묶음이 첫 화면에서 섹터 목록을 받고, 결과 분석 탭을 고르면 예외 기록을 받는다(T098)
    api.exceptions.mockResolvedValue(resultExceptions)
    api.catalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  it('KOSPI 참고선을 켜기 전에는 그 API를 부르지 않고 다음 탐색 영역도 없다', async () => {
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

  it('비교 대상 고르기에서 KOSPI를 켜면 주소에 남고 서버 값을 참고선으로 그리며, 끄면 선과 주소가 함께 빠진다', async () => {
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

    await user.click(await screen.findByRole('button', { name: '비교 대상 고르기' }))
    const toggle = await screen.findByRole('checkbox', { name: 'KOSPI 함께 보기' })
    await user.click(toggle)

    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}?index=KOSPI`)
    expect(await screen.findByRole('img', { name: /KOSPI\(참고\)를 시작 금액으로 환산한 선/ })).toBeTruthy()
    expect(api.index).toHaveBeenCalledTimes(1)
    // 비교 대상이 아니다 — 결과를 새로 접수하지 않는다.
    expect(api.create).not.toHaveBeenCalled()

    await user.click(toggle)
    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}`)
    expect(screen.queryByRole('img', { name: /KOSPI\(참고\)/ })).toBeNull()
  })

  it('주소에 ?index=KOSPI 가 있으면 새로 열어도 참고선이 복원된다', async () => {
    api.index.mockResolvedValue({
      available: true,
      reason: null,
      series: [{ date: '2023-07-03', value: '10000000.00' }],
    })
    renderResult({ search: '?index=KOSPI' })

    expect(await screen.findByRole('img', { name: /KOSPI\(참고\)/ })).toBeTruthy()
    expect(api.index).toHaveBeenCalledTimes(1)
    expect(api.index.mock.calls[0][0]).toBe(resultToken)
  })

  it('지수 기간 계열을 받을 수 없으면 선 대신 서버 이유에 맞는 안내를 보이고 다시 부르지 않는다', async () => {
    api.index.mockResolvedValue({
      available: false,
      reason: 'INDEX_SERIES_UNAVAILABLE',
      series: [],
    })
    const user = userEvent.setup()
    renderResult({ search: '?index=KOSPI' })

    expect(await screen.findByText(/같은 기간의 KOSPI 자료를 받을 수 없어 참고선을/)).toBeTruthy()
    expect(screen.queryByRole('img', { name: /KOSPI\(참고\)/ })).toBeNull()
    await user.click(screen.getByRole('tab', { name: '결과 분석' }))
    expect(api.index).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}?index=KOSPI`)
  })
})
