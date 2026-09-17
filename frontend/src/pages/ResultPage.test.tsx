import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ResultError } from '@/api/result'
import { RESULT_POLL_INTERVAL_MS } from '@/components/result/constants'
import { EXPIRED_MESSAGE, NOT_FOUND_MESSAGE } from '@/components/result/ResultUnavailableNotice'
import {
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  resultDetailRunning,
  resultTimeseries,
  resultToken,
} from '@/test/resultFixtures'
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
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

function renderResult(token = resultToken) {
  return render(
    <MemoryRouter initialEntries={[`/results/${token}`]}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ResultPage', () => {
  beforeEach(() => {
    for (const mock of Object.values(apiMocks)) mock.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('완료 결과는 상세와 시계열만 한 번씩 부르고 네 요소를 보인다', async () => {
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
      new ResultError('결과가 더 이상 남아 있지 않습니다.', { code: 'RESULT_EXPIRED', status: 410 }),
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
        <GoToOther />
        <Routes>
          <Route path="/results/:token" element={<ResultPage />} />
        </Routes>
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
})
