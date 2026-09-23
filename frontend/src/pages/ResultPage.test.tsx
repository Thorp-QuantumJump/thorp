import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ResultError } from '@/api/result'
import { RESULT_POLL_INTERVAL_MS } from '@/components/result/constants'
import { LOSS_WINDOW_NONE, LOSS_WINDOW_UNAVAILABLE } from '@/components/result/LossWindowView'
import { EXPIRED_MESSAGE, NOT_FOUND_MESSAGE } from '@/components/result/ResultUnavailableNotice'
import {
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  resultDetailRunning,
  resultExceptions,
  resultPositions,
  resultSectorCatalog,
  resultTimeseries,
  resultToken,
  resultTradePage,
  resultWeights,
} from '@/test/resultFixtures'
import { registryFixture } from '@/test/strategyRegistryFixtures'
import type { PublicResultDetail } from '@/types/result'
import { ResultPage } from './ResultPage'

/** 005 T037. SC-001·SC-008·SC-009, quickstart 1·7·8. */

const apiMocks = vi.hoisted(() => ({
  fetchResult: vi.fn(),
  fetchResultTimeseries: vi.fn(),
  fetchResultTrades: vi.fn(),
  fetchResultPositions: vi.fn(),
  fetchResultWeights: vi.fn(),
  fetchResultExceptions: vi.fn(),
  retryResult: vi.fn(),
}))

vi.mock('@/api/result', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/result')>()
  return { ...actual, ...apiMocks }
})
// 첫 화면이 등록표·섹터 목록·대표 상품 정보를 부르지 않는지 본다(SC-001). B07의 보기와 이동이 쓴다.
const otherMocks = vi.hoisted(() => ({
  fetchStrategyRegistry: vi.fn(),
  fetchSectorCatalog: vi.fn(),
  fetchInstrumentProfile: vi.fn(),
}))

vi.mock('@/api/strategyRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategyRegistry')>()
  return { ...actual, fetchStrategyRegistry: otherMocks.fetchStrategyRegistry }
})
vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return {
    ...actual,
    fetchSectorCatalog: otherMocks.fetchSectorCatalog,
    fetchInstrumentProfile: otherMocks.fetchInstrumentProfile,
  }
})
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

/**
 * 결과 화면은 비로그인도 여는 공개 경로지만 실제 앱에서는 `main.tsx`의 `AuthProvider` 안에서 돈다. 006의
 * '기록으로 내보내기'가 로그인 여부로 갈리므로(T045a) 테스트도 같은 자리에 둔다 — 비로그인이 기본이다.
 */
const anonymousAuth = {
  user: null,
  status: 'anonymous',
  login: async () => {},
  logout: async () => {},
  refresh: async () => {},
} as unknown as AuthContextValue

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="현재 주소">{location.pathname + location.search}</output>
}

function renderResult(token = resultToken) {
  return render(
    <MemoryRouter initialEntries={[`/results/${token}`]}>
      <AuthContext.Provider value={anonymousAuth}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
        <Route path="/strategy" element={<h1>전략 설정 화면</h1>} />
        <Route path="/diagnosis/sectors" element={<h1>섹터 선택 화면</h1>} />
      </Routes>
      <LocationProbe />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('ResultPage', () => {
  beforeEach(() => {
    for (const mock of [...Object.values(apiMocks), ...Object.values(otherMocks)]) mock.mockReset()
    // 탭 묶음이 섹터 목록을 첫 화면에서 한 번 받는다(T098). 개별 테스트가 덮어쓴다.
    otherMocks.fetchSectorCatalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('완료 결과는 상세·시계열·섹터 목록만 한 번씩 부르고 요약 탭에 네 요소를 보인다', async () => {
    apiMocks.fetchResult.mockResolvedValue(resultDetailCompleted)
    apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)

    renderResult()

    expect(await screen.findByText(/비용을 뺀 뒤 전체 수익률은 16\.67%/)).not.toBeNull()
    expect(screen.getByLabelText('분석 조건')).not.toBeNull()
    expect(screen.getByText('16.67%')).not.toBeNull()
    expect(screen.getByText('자료 출처: 한국거래소 통계정보')).not.toBeNull()

    expect(apiMocks.fetchResult).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResult.mock.calls[0][0]).toBe(resultToken)
    expect(apiMocks.fetchResultTimeseries).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultPositions).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultWeights).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultExceptions).not.toHaveBeenCalled()
    expect(otherMocks.fetchStrategyRegistry).not.toHaveBeenCalled()
    expect(otherMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
    // 탭 묶음이 상세 탭 셋을 위해 한 번 받아 둔다(SC-001, T098).
    expect(otherMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('tab', { name: '요약' }).getAttribute('aria-selected')).toBe('true')
  })

  it('화면 어디에도 004의 내부 실행 식별자가 없다', async () => {
    apiMocks.fetchResult.mockResolvedValue(resultDetailCompleted)
    apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)

    const { container } = renderResult()
    await screen.findByText(/비용을 뺀 뒤 전체 수익률은/)

    expect(container.innerHTML).not.toContain('BT-')
    expect(container.innerHTML).not.toContain('backtestId')
  })

  it('만료되면 다시 분석할 진입점을 보이고 숫자를 그리지 않는다', async () => {
    apiMocks.fetchResult.mockRejectedValue(
      new ResultError('결과가 더 이상 남아 있지 않습니다.', {
        code: 'RESULT_EXPIRED',
        status: 410,
      }),
    )

    const { container } = renderResult()

    expect(await screen.findByRole('heading', { name: EXPIRED_MESSAGE })).not.toBeNull()
    expect(
      screen.getByRole('link', { name: '조건을 다시 골라 분석하기' }).getAttribute('href'),
    ).toBe('/strategy')
    expect(container.textContent).not.toMatch(/\d%|\d원/)
    expect(apiMocks.fetchResultTimeseries).not.toHaveBeenCalled()
  })

  it('없는 주소는 만료와 다른 문구로 안내한다', async () => {
    apiMocks.fetchResult.mockRejectedValue(
      new ResultError('없음', { code: 'RESOURCE_NOT_FOUND', status: 404 }),
    )

    const { container } = renderResult('9f2c1a7b4e0d83526af1c9d0b7e34513')

    expect(await screen.findByRole('heading', { name: NOT_FOUND_MESSAGE })).not.toBeNull()
    expect(screen.queryByText(EXPIRED_MESSAGE)).toBeNull()
    expect(screen.queryByRole('link', { name: '조건을 다시 골라 분석하기' })).toBeNull()
    expect(container.textContent).not.toMatch(/\d%|\d원/)
  })

  it.each([
    [404, NOT_FOUND_MESSAGE],
    [400, NOT_FOUND_MESSAGE],
    [410, EXPIRED_MESSAGE],
  ])('응답 봉투 없이 %i 가 와도 상태 코드로 안내를 고른다', async (status, message) => {
    // 프록시나 보안 필터가 먼저 답하면 오류 이름이 없다. 다시 불러오기를 주면 같은 실패를 반복한다.
    apiMocks.fetchResult.mockRejectedValue(new ResultError('요청 실패', { status }))

    renderResult()

    expect(await screen.findByRole('heading', { name: message })).not.toBeNull()
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  // 토큰이 바뀐 직후 한 번의 렌더에 앞 결과가 남는 문제는 `ResultPage`의 `key`가 막는다. 그 한 번은
  // act가 효과까지 흘려보낸 뒤라 jsdom에서 관찰되지 않으므로, 여기서는 새 토큰으로 다시 부르는지만 본다.
  it('토큰이 바뀌면 불러오는 중으로 돌아가 새 토큰으로 다시 부른다', async () => {
    const otherToken = 'b41e0c9a7d2f35618ce4a09b7f1d2c63'
    let resolveOther: (value: unknown) => void = () => {}
    apiMocks.fetchResult.mockImplementation((token: string) =>
      token === resultToken
        ? Promise.resolve(resultDetailCompleted)
        : new Promise((resolve) => {
            resolveOther = resolve
          }),
    )
    apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)

    function GoToOther() {
      const navigate = useNavigate()
      return (
        <button type="button" onClick={() => navigate(`/results/${otherToken}`)}>
          다른 결과
        </button>
      )
    }

    render(
      <MemoryRouter initialEntries={[`/results/${resultToken}`]}>
        <AuthContext.Provider value={anonymousAuth}>
          <GoToOther />
          <Routes>
            <Route path="/results/:token" element={<ResultPage />} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>,
    )
    expect(await screen.findByText('16.67%')).not.toBeNull()

    await act(async () => {
      screen.getByRole('button', { name: '다른 결과' }).click()
    })

    expect(screen.queryByText('16.67%')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe('결과를 불러오는 중입니다.')
    expect(apiMocks.fetchResult.mock.calls.map((call) => call[0])).toEqual([
      resultToken,
      otherToken,
    ])

    await act(async () => {
      resolveOther({ ...resultDetailCompleted, publicToken: otherToken })
    })
    expect(await screen.findByText('16.67%')).not.toBeNull()
  })

  it('형식이 틀린 토큰도 잘못된 주소로 안내한다', async () => {
    apiMocks.fetchResult.mockRejectedValue(
      new ResultError('형식 오류', { code: 'INVALID_INPUT', status: 400 }),
    )

    renderResult('not-a-token')

    expect(await screen.findByRole('heading', { name: NOT_FOUND_MESSAGE })).not.toBeNull()
  })

  it('계산 중이면 폴링하다 완료되면 결과를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    apiMocks.fetchResult
      .mockResolvedValueOnce(resultDetailRunning)
      .mockResolvedValueOnce(resultDetailCompleted)
    apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)

    renderResult()

    expect(await screen.findByText(/전체 380거래일 중 180거래일을 마쳤습니다/)).not.toBeNull()
    expect(apiMocks.fetchResultTimeseries).not.toHaveBeenCalled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(RESULT_POLL_INTERVAL_MS)
    })

    expect(await screen.findByText(/비용을 뺀 뒤 전체 수익률은 16\.67%/)).not.toBeNull()
    expect(apiMocks.fetchResult).toHaveBeenCalledTimes(2)
    expect(apiMocks.fetchResultTimeseries).toHaveBeenCalledTimes(1)
  })

  it('시작 전에 취소된 결과는 하위 자원을 부르지 않고 숫자 없이 사유를 보인다', async () => {
    apiMocks.fetchResult.mockResolvedValue(resultDetailCancelledNoSummary)

    const { container } = renderResult()

    expect(await screen.findByText(/취소 시점까지의 지표는 계산되지 않았습니다/)).not.toBeNull()
    expect(apiMocks.fetchResultTimeseries).not.toHaveBeenCalled()
    expect(container.textContent).not.toMatch(/\d%|\d원/)
  })

  it('실패 사유를 보이고 다시 시도하면 새 공개 결과 주소로 이동한다', async () => {
    const newToken = 'b41e0c9a7d2f35618ce4a09b7f1d2c63'
    const failed: PublicResultDetail = {
      ...resultDetailCompleted,
      status: 'FAILED',
      summary: null,
      failure: { code: 'ENGINE_ERROR', message: '계산 엔진이 응답하지 않았습니다.' },
      weightsSource: null,
      exceptionKinds: null,
    }
    apiMocks.fetchResult
      .mockResolvedValueOnce(failed)
      .mockResolvedValueOnce({ ...resultDetailRunning, publicToken: newToken })
    apiMocks.retryResult.mockResolvedValue({
      publicToken: newToken,
      status: 'QUEUED',
      resultSource: 'NEW',
      retryOfToken: resultToken,
    })
    const user = userEvent.setup()

    renderResult()
    expect(await screen.findByText(/계산 엔진이 응답하지 않았습니다/)).not.toBeNull()
    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(apiMocks.retryResult).toHaveBeenCalledWith(resultToken)
    // 주소는 클릭 뒤 retryResult → navigate → 렌더를 거쳐 바뀐다. 이미 있는 탐침을 바로 읽으면 바쁜 실행에서
    // 옛 주소를 본다 — 바뀔 때까지 기다린다.
    await waitFor(() =>
      expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${newToken}`),
    )
  })

  it.each([
    ['점이 하나도 없으면', []],
    [
      '모든 날의 자산가치가 비어 있으면',
      resultTimeseries.points.map((point) => ({ ...point, equity: '' })),
    ],
  ])('시계열을 받았어도 %s 그래프 대신 그 사실을 알린다', async (_case, points) => {
    apiMocks.fetchResult.mockResolvedValue(resultDetailCompleted)
    apiMocks.fetchResultTimeseries.mockResolvedValue({ partial: true, points })

    const { container } = renderResult()

    expect(
      await screen.findByText('받은 시계열에 자산가치 기록이 없어 그래프를 그리지 않았습니다.'),
    ).not.toBeNull()
    expect(container.querySelector('[role="img"]')).toBeNull()
  })

  it('연결에 실패하면 다시 불러오기를 두고 누르면 다시 부른다', async () => {
    apiMocks.fetchResult
      .mockRejectedValueOnce(new ResultError('서버에 연결할 수 없습니다.'))
      .mockResolvedValueOnce(resultDetailCompleted)
    apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)

    renderResult()

    const retry = await screen.findByRole('button', { name: '다시 불러오기' })
    await act(async () => {
      retry.click()
    })

    expect(await screen.findByText(/비용을 뺀 뒤 전체 수익률은/)).not.toBeNull()
    expect(apiMocks.fetchResult).toHaveBeenCalledTimes(2)
  })

  describe('자세히 보기와 다음 분석 (B07)', () => {
    /** 요약의 최대 하락 폭을 시계열 저점과 맞춘 결과. 구간을 찾을 수 있다 */
    const troughPoint = resultTimeseries.points.reduce((lowest, point) =>
      Number(point.drawdown) < Number(lowest.drawdown) ? point : lowest,
    )
    const matchingDetail: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: resultDetailCompleted.summary && {
        ...resultDetailCompleted.summary,
        maxDrawdown: troughPoint.drawdown,
      },
    }

    beforeEach(() => {
      apiMocks.fetchResult.mockResolvedValue(matchingDetail)
      apiMocks.fetchResultTimeseries.mockResolvedValue(resultTimeseries)
      apiMocks.fetchResultWeights.mockResolvedValue(resultWeights)
      apiMocks.fetchResultExceptions.mockResolvedValue(resultExceptions)
      apiMocks.fetchResultTrades.mockResolvedValue(resultTradePage)
      apiMocks.fetchResultPositions.mockResolvedValue(resultPositions)
      otherMocks.fetchSectorCatalog.mockResolvedValue({
        status: 'loaded',
        catalog: resultSectorCatalog,
      })
      otherMocks.fetchInstrumentProfile.mockResolvedValue({
        status: 'notReady',
        message: '준비 중',
      })
      otherMocks.fetchStrategyRegistry.mockResolvedValue(registryFixture)
      window.sessionStorage.clear()
    })

    it('요약 탭이 첫 화면에 열려 있고 결과 분석·설정·근거 탭은 고를 때 제 자료를 받으며 주소는 그대로다 (SC-006, T098)', async () => {
      const user = userEvent.setup()
      renderResult()
      const summaryTab = await screen.findByRole('tab', { name: '요약' })
      const before = screen.getByLabelText('현재 주소').textContent

      expect(summaryTab.getAttribute('aria-selected')).toBe('true')
      expect(within(screen.getByRole('tabpanel')).getByText('16.67%')).not.toBeNull()
      expect(apiMocks.fetchResultExceptions).not.toHaveBeenCalled()

      await user.click(screen.getByRole('tab', { name: '결과 분석' }))
      expect(await screen.findByText('HALT_DEFERRED')).not.toBeNull()
      expect(apiMocks.fetchResultExceptions).toHaveBeenCalledTimes(1)
      expect(apiMocks.fetchResultWeights).not.toHaveBeenCalled()

      await user.click(screen.getByRole('tab', { name: '설정·근거' }))

      const panel = screen.getByRole('tabpanel')
      expect(await within(panel).findByText('44.73%')).not.toBeNull()
      expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(1)
      expect(screen.getByLabelText('현재 주소').textContent).toBe(before)
      // 대표 상품 정보도 탭 안에서 펼치고 001 화면으로 옮기지 않는다.
      await user.click(await within(panel).findByRole('button', { name: '항구 · 합성 항구 ETF' }))
      expect(screen.getByLabelText('현재 주소').textContent).toBe(before)
      // 지나온 탭의 내용은 내리지 않고 숨긴다.
      expect(screen.getAllByRole('tabpanel', { hidden: true })).toHaveLength(3)
    })

    it('손실이 커진 때 보기는 고점~저점 구간의 거래와 저점일 보유를 연다', async () => {
      const user = userEvent.setup()
      renderResult()

      await user.click(await screen.findByRole('tab', { name: '손실이 커진 때' }))

      const view = await screen.findByRole('region', { name: /손실이 커진 때: .+ ~ / })
      expect(within(view).getByRole('heading', { level: 2 }).textContent).toContain(
        troughPoint.date,
      )
      await within(view).findByRole('table', { name: '구간 거래' })
      // 계약 예시 계열에서 저점 직전 `drawdown`이 0인 마지막 날이다(`resultFixtures.ts`).
      expect(apiMocks.fetchResultTrades.mock.calls[0][1]).toMatchObject({
        from: '2025-03-10',
        to: troughPoint.date,
      })
      expect(apiMocks.fetchResultPositions.mock.calls[0][1]).toBe(troughPoint.date)
    })

    it('하락이 없던 결과는 구간을 확인하지 못한 것과 다른 이유를 적는다', async () => {
      apiMocks.fetchResult.mockResolvedValue({
        ...matchingDetail,
        summary: matchingDetail.summary && { ...matchingDetail.summary, maxDrawdown: '0.000000' },
      })
      renderResult()

      const tab = await screen.findByRole('tab', { name: '손실이 커진 때' })
      expect(tab.getAttribute('aria-disabled')).toBe('true')
      const reason = document.getElementById(tab.getAttribute('aria-describedby') ?? '')
      expect(reason?.textContent).toBe(LOSS_WINDOW_NONE)
    })

    it('최대 하락 구간을 확인할 수 없으면 손실이 커진 때 보기를 비활성하고 이유를 적는다', async () => {
      // 요약 값을 시계열 저점과 다르게 만들어 이 계열로 숫자를 설명할 수 없는 경우를 만든다(R-05).
      apiMocks.fetchResult.mockResolvedValue({
        ...resultDetailCompleted,
        summary: resultDetailCompleted.summary && {
          ...resultDetailCompleted.summary,
          maxDrawdown: '-0.030000',
        },
      })
      renderResult()

      const tab = await screen.findByRole('tab', { name: '손실이 커진 때' })
      expect(tab.getAttribute('aria-disabled')).toBe('true')
      const reasonId = tab.getAttribute('aria-describedby') ?? ''
      expect(document.getElementById(reasonId)?.textContent).toBe(LOSS_WINDOW_UNAVAILABLE)
    })

    it('제목은 백테스트 결과이고 전략 선택으로 돌아가는 길이 있다', async () => {
      renderResult()

      expect(await screen.findByRole('heading', { level: 1, name: '백테스트 결과' })).not.toBeNull()
      expect(screen.getByRole('link', { name: '전략 선택' }).getAttribute('href')).toBe('/strategy')
    })

    it('다른 조건으로 비교는 조건을 보관하고 설정 화면으로 옮긴다', async () => {
      const user = userEvent.setup()
      renderResult()

      await user.click(await screen.findByRole('button', { name: '다른 조건으로 비교' }))

      expect(await screen.findByRole('heading', { name: '전략 설정 화면' })).not.toBeNull()
      expect(screen.getByLabelText('현재 주소').textContent).toBe('/strategy')
      expect(window.sessionStorage.getItem('thorp.strategySettings')).toContain(
        resultDetailCompleted.config.strategyId,
      )
    })

    it('대응표가 다른 결과는 나머지 조건만 채워 두고 섹터·비중 단계 연결을 보인다', async () => {
      otherMocks.fetchSectorCatalog.mockResolvedValue({
        status: 'loaded',
        catalog: { ...resultSectorCatalog, mapVersion: 'SECTOR-ETF-TEST-2' },
      })
      const user = userEvent.setup()
      renderResult()

      await user.click(await screen.findByRole('button', { name: '다른 조건으로 비교' }))

      const alert = await screen.findByRole('alert')
      expect(alert.textContent).toContain('섹터는 넘기지 않았습니다')
      expect(alert.textContent).toContain('섹터만 다시 골라 주세요')
      expect(screen.getByLabelText('현재 주소').textContent).toBe(`/results/${resultToken}`)
      // 섹터 선택은 건드리지 않고, 대응표와 무관한 조건은 설정 화면에 채워 둔다.
      expect(window.sessionStorage.getItem('thorp.diagnosis.sectorSelection')).toBeNull()
      expect(window.sessionStorage.getItem('thorp.strategySettings')).toContain(
        resultDetailCompleted.config.strategyId,
      )

      await user.click(within(alert).getByRole('link', { name: '섹터·비중 단계로 가기' }))
      expect(await screen.findByRole('heading', { name: '섹터 선택 화면' })).not.toBeNull()
    })

    it('다시 시도하면 풀릴 수 있는 실패에는 섹터·비중 단계 연결을 두지 않는다', async () => {
      otherMocks.fetchSectorCatalog.mockResolvedValue({
        status: 'failed',
        kind: 'network',
        code: null,
        message: '연결 실패',
        retryable: true,
      })
      const user = userEvent.setup()
      renderResult()

      await user.click(await screen.findByRole('button', { name: '다른 조건으로 비교' }))

      const alert = await screen.findByRole('alert')
      expect(alert.textContent).toContain('섹터 목록을 불러오지 못했습니다')
      expect(within(alert).queryByRole('link')).toBeNull()
    })

    it('주 모양 버튼은 비교 대상 고르기 하나고 나머지 보기는 탭이다 (착수 전에 알아둘 것 ⑧, T098)', async () => {
      renderResult()
      await screen.findByRole('tablist', { name: '결과 보기' })

      expect(screen.getByRole('button', { name: '다른 조건으로 비교' }).getAttribute('data-variant')).toBe(
        'outline',
      )
      for (const name of [
        '요약',
        '결과 분석',
        '설정·근거',
        '전체 거래 내역',
        '손실이 커진 때',
        'KOSPI 비교',
        '목표 위험 맞춤',
      ]) {
        expect(screen.getByRole('tab', { name }), name).not.toBeNull()
      }
      expect(screen.queryByRole('button', { name: '자세히 보기' })).toBeNull()
      expect(
        document.querySelectorAll('[data-slot="button"][data-variant="default"]'),
      ).toHaveLength(1)
      expect(
        document.querySelector('[data-slot="button"][data-variant="default"]')?.textContent,
      ).toContain('비교 대상 고르기')
    })

    it('볼 결과가 없으면 탭 없이 요약 내용만 두고 상세 자료를 받지 않는다', async () => {
      apiMocks.fetchResult.mockResolvedValue(resultDetailRunning)
      renderResult()
      await screen.findByText(/전체 380거래일 중 180거래일을 마쳤습니다/)

      expect(screen.queryByRole('tablist')).toBeNull()
      expect(screen.getByLabelText('분석 조건')).not.toBeNull()
      expect(apiMocks.fetchResultExceptions).not.toHaveBeenCalled()
      expect(otherMocks.fetchSectorCatalog).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: '다른 조건으로 비교' })).not.toBeNull()
    })
  })
})
