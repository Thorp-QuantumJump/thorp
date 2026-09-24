import { StrictMode } from 'react'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthContext } from '@/auth/auth-context'
import { anonymousAuthContext } from '@/test/authFixtures'
import { BacktestRequestError } from '@/api/backtest'
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

function renderResult({ strict = false, search }: { strict?: boolean; search?: string } = {}) {
  const route = <ResultRoute search={search} />
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

  it('KOSPI 참고선을 켜거나 목표 위험 맞춤 탭을 고르기 전에는 그 API를 부르지 않고 다음 탐색 영역도 없다', async () => {
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

  it('목표 위험은 등록표 기본값을 decimal로 보내고 별도 표지와 세 숫자만 보인다', async () => {
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1))
    expect(api.create.mock.calls[0][0].conditions).toMatchObject({
      scenario: 'TARGET_RISK',
      scenarioParams: {
        targetVolatility: (registryFixture.analysisConditions.targetVolatility.default / 100).toFixed(4),
      },
    })
    const panel = await screen.findByRole('region', { name: '목표 위험 맞춤' })
    expect(within(panel).getByRole('heading', { name: '목표 위험 맞춤 시나리오' })).toBeTruthy()
    expect(within(panel).getByText('12.35%')).toBeTruthy()
    expect(within(panel).getByText('-3.21%')).toBeTruthy()
    expect(within(panel).getByText('23,457원')).toBeTruthy()
    expect(api.series).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}`)
  })

  it('StrictMode에서도 목표 위험 요청의 완료 응답을 반영한다', async () => {
    const user = userEvent.setup()
    renderResult({ strict: true })

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1))
    const panel = await screen.findByRole('region', { name: '목표 위험 맞춤' })
    expect(await within(panel).findByText('12.35%')).toBeTruthy()
    expect(within(panel).queryByText('별도 시나리오 결과를 준비하고 있습니다.')).toBeNull()
  })

  it.each(['QUEUED', 'RUNNING'] as const)(
    '목표 위험 결과가 %s이면 준비 상태를 계속 표시한다',
    async (status) => {
      let resolveTarget: ((detail: PublicResultDetail) => void) | null = null
      api.detail.mockImplementation((token: string) => {
        if (token === resultToken) return Promise.resolve(resultDetailCompleted)
        return new Promise<PublicResultDetail>((resolve) => {
          resolveTarget = resolve
        })
      })
      const user = userEvent.setup()
      renderResult()

      await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))
      const panel = await screen.findByRole('region', { name: '목표 위험 맞춤' })
      expect(
        within(panel).getByText('별도 시나리오 결과를 준비하고 있습니다.'),
      ).toBeTruthy()

      await act(async () => {
        resolveTarget?.(targetResult({ status, summary: null }))
      })

      expect(
        within(panel).getByText('별도 시나리오 결과를 준비하고 있습니다.'),
      ).toBeTruthy()
      expect(within(panel).queryByLabelText('목표 위험 맞춤 핵심 결과')).toBeNull()
    },
  )

  it('목표 위험 계산 제한은 숫자를 만들지 않고 사유를 보인다', async () => {
    api.detail.mockImplementation(async (token: string) =>
      token === resultToken
        ? resultDetailCompleted
        : targetResult({
            status: 'FAILED',
            summary: null,
            failure: { code: 'ESTIMATION_FAILED', message: '추정할 관측치가 부족합니다.' },
          }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(await screen.findByText(/자료가 충분하지 않아 목표 위험을 계산하지 못했습니다/)).toBeTruthy()
    expect(screen.queryByLabelText('목표 위험 맞춤 핵심 결과')).toBeNull()
  })

  it('등록표 조회가 실패하면 사용자가 다시 요청할 수 있다', async () => {
    api.registry.mockRejectedValueOnce(new Error('등록표를 불러오지 못했습니다.'))
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(await screen.findByText('등록표를 불러오지 못했습니다.')).toBeTruthy()
    expect(api.create).not.toHaveBeenCalled()

    api.registry.mockResolvedValueOnce(registryFixture)
    await user.click(screen.getByRole('button', { name: '다시 요청하기' }))

    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('12.35%')).toBeTruthy()
  })

  it('일반 실행 실패도 빈 패널 대신 사유를 보인다', async () => {
    api.detail.mockImplementation(async (token: string) =>
      token === resultToken
        ? resultDetailCompleted
        : targetResult({
            status: 'FAILED',
            summary: null,
            failure: { code: 'SYSTEM_FAILURE', message: '작업 처리 중 오류가 발생했습니다.' },
          }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(await screen.findByText(/목표 위험 시나리오를 완료하지 못했습니다/)).toBeTruthy()
    expect(screen.getByText(/작업 처리 중 오류가 발생했습니다/)).toBeTruthy()
    expect(screen.queryByLabelText('목표 위험 맞춤 핵심 결과')).toBeNull()
  })

  it('요약 없이 취소된 목표 위험 결과는 사유와 다시 요청하기를 보인다', async () => {
    api.detail.mockImplementation(async (token: string) =>
      token === resultToken
        ? resultDetailCompleted
        : targetResult({
            status: 'CANCELLED',
            partial: true,
            summary: null,
            summaryUnavailable: 'CANCELLED_BEFORE_START',
            failure: null,
          }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(
      await screen.findByText('별도 시나리오가 취소되어 핵심 결과를 계산하지 못했습니다.'),
    ).toBeTruthy()
    expect(screen.queryByLabelText('목표 위험 맞춤 핵심 결과')).toBeNull()

    await user.click(screen.getByRole('button', { name: '다시 요청하기' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
  })

  it('접수된 결과 조회가 실패하면 사용자 요청으로 다시 불러온다', async () => {
    let targetReads = 0
    api.detail.mockImplementation(async (token: string) => {
      if (token === resultToken) return resultDetailCompleted
      targetReads += 1
      if (targetReads === 1) throw new Error('일시적인 조회 실패')
      return targetResult()
    })
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(await screen.findByText('결과를 불러오지 못했습니다.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '결과 다시 불러오기' }))

    expect(await screen.findByText('12.35%')).toBeTruthy()
    expect(targetReads).toBe(2)
  })

  it('429는 자동 재요청하지 않고 대기 시간을 보인 뒤 사용자 요청으로만 다시 보낸다', async () => {
    api.create.mockRejectedValueOnce(
      new BacktestRequestError('api', '실행 한도에 도달했습니다.', {
        code: 'EXECUTION_LIMIT',
        status: 429,
        details: { retryAfterSeconds: 30 },
      }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))
    expect(await screen.findByText(/30초 뒤 다시 요청할 수 있습니다/)).toBeTruthy()
    expect(api.create).toHaveBeenCalledTimes(1)

    api.create.mockResolvedValueOnce({ publicToken: targetToken, status: 'QUEUED' })
    await user.click(screen.getByRole('button', { name: '다시 요청하기' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
  })

  it('422의 필요 워밍업은 사용자 확인 뒤에만 구조화된 값으로 다시 요청한다', async () => {
    api.create.mockRejectedValueOnce(
      new BacktestRequestError('api', '준비 기간이 부족합니다.', {
        code: 'INSUFFICIENT_HISTORY',
        status: 422,
        details: { requiredWarmupTradingDays: 63 },
      }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))
    expect(await screen.findByText('준비 기간이 부족합니다.')).toBeTruthy()
    expect(api.create).toHaveBeenCalledTimes(1)

    api.create.mockResolvedValueOnce({ publicToken: targetToken, status: 'QUEUED' })
    await user.click(screen.getByRole('button', { name: '필요 기간으로 다시 요청하기' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
    expect(api.create.mock.calls[1][0].conditions).toMatchObject({
      warmupTradingDays: 63,
      start: resultDetailCompleted.config.start,
    })
  })

  it('422의 필요 시작일이 종료일 뒤면 잘못된 기간으로 자동 재요청하지 않는다', async () => {
    api.create.mockRejectedValueOnce(
      new BacktestRequestError('api', '요청 기간 안에 평가할 수 있는 날이 없습니다.', {
        code: 'INSUFFICIENT_HISTORY',
        status: 422,
        details: { requiredWarmupTradingDays: 63, requiredStartDate: '2025-01-02' },
      }),
    )
    const user = userEvent.setup()
    renderResult()

    await user.click(await screen.findByRole('tab', { name: '목표 위험 맞춤' }))

    expect(await screen.findByText('요청 기간 안에 평가할 수 있는 날이 없습니다.')).toBeTruthy()
    expect(screen.getByText(/2025-01-02 이후를 포함하도록 분석 기간을 다시 선택해야 합니다/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '필요 기간으로 다시 요청하기' })).toBeNull()
    expect(api.create).toHaveBeenCalledTimes(1)
  })
})
