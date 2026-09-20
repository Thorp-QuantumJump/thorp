import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resultDetailCompleted, resultToken } from '@/test/resultFixtures'

/** 005 T038. contracts "경로 대응"·"오류". */

const clientMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  runCsrfProtectedMutation: vi.fn(),
}))

vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client')>()
  return {
    ...actual,
    apiClient: { get: clientMocks.get, post: clientMocks.post },
    runCsrfProtectedMutation: clientMocks.runCsrfProtectedMutation,
  }
})

const {
  ResultError,
  fetchResult,
  fetchResultExceptions,
  fetchResultIndexComparison,
  fetchResultPositions,
  fetchResultTimeseries,
  fetchResultTrades,
  fetchResultWeights,
  normalizeResultError,
  retryResult,
} = await import('./result')
const { ApiClientError } = await import('./client')

function ok<T>(data: T) {
  return { data: { success: true, data, error: null } }
}

function axiosFailure(status: number, body: unknown): AxiosError {
  const headers = new AxiosHeaders()
  return new AxiosError('failed', String(status), { headers }, null, {
    status,
    statusText: '',
    headers,
    config: { headers },
    data: body,
  })
}

describe('api/result', () => {
  beforeEach(() => {
    clientMocks.get.mockReset()
    clientMocks.post.mockReset()
    clientMocks.runCsrfProtectedMutation.mockReset()
    clientMocks.runCsrfProtectedMutation.mockImplementation((request: () => Promise<unknown>) =>
      request(),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('토큰을 그대로 경로에 쓴다', async () => {
    clientMocks.get.mockResolvedValue(ok(resultDetailCompleted))
    const controller = new AbortController()

    await expect(fetchResult(resultToken, controller.signal)).resolves.toEqual(resultDetailCompleted)
    expect(clientMocks.get).toHaveBeenCalledWith(`/results/${resultToken}`, {
      signal: controller.signal,
    })
  })

  it('하위 자원 경로를 조립한다', async () => {
    clientMocks.get.mockResolvedValue(ok({}))

    await fetchResultTimeseries(resultToken)
    await fetchResultIndexComparison(resultToken)
    await fetchResultWeights(resultToken)
    await fetchResultExceptions(resultToken)
    await fetchResultPositions(resultToken, '2024-03-29')

    expect(clientMocks.get.mock.calls.map((call) => call[0])).toEqual([
      `/results/${resultToken}/timeseries`,
      `/results/${resultToken}/index-comparison`,
      `/results/${resultToken}/weights`,
      `/results/${resultToken}/exceptions`,
      `/results/${resultToken}/positions?date=2024-03-29`,
    ])
  })

  it.each(['..', '../backtests/1', 'ABCDEF0123456789ABCDEF0123456789', `${resultToken}0`, ''])(
    '형식이 틀린 토큰 %j 은 요청하지 않고 400 INVALID_INPUT이다',
    async (token) => {
      await expect(fetchResult(token)).rejects.toMatchObject({ code: 'INVALID_INPUT', status: 400 })
      await expect(fetchResultTimeseries(token)).rejects.toMatchObject({ code: 'INVALID_INPUT' })
      await expect(retryResult(token)).rejects.toMatchObject({ code: 'INVALID_INPUT' })

      expect(clientMocks.get).not.toHaveBeenCalled()
      expect(clientMocks.runCsrfProtectedMutation).not.toHaveBeenCalled()
    },
  )

  it('거래 질의의 size·from·to 등을 질의 문자열에 싣고 정하지 않은 항목은 뺀다', async () => {
    clientMocks.get.mockResolvedValue(ok({ content: [] }))

    await fetchResultTrades(resultToken, {
      page: 2,
      size: 50,
      instrumentId: '900001.XKRX',
      status: 'CLOSED',
      from: '2024-01-02',
      to: '2024-03-29',
    })
    await fetchResultTrades(resultToken)

    expect(clientMocks.get.mock.calls[0][0]).toBe(
      `/results/${resultToken}/trades?page=2&size=50&instrumentId=900001.XKRX&status=CLOSED&from=2024-01-02&to=2024-03-29`,
    )
    expect(clientMocks.get.mock.calls[1][0]).toBe(`/results/${resultToken}/trades`)
  })

  it.each([
    [410, 'RESULT_EXPIRED'],
    [404, 'RESOURCE_NOT_FOUND'],
    [400, 'INVALID_INPUT'],
    [409, 'RESULT_NOT_READY'],
    [409, 'BACKTEST_FAILED'],
    [409, 'NO_PARTIAL_RESULT'],
  ] as const)('%i %s 를 코드와 상태로 정규화한다', async (status, code) => {
    clientMocks.get.mockRejectedValue(
      axiosFailure(status, { success: false, data: null, error: { code, message: '안내 문구' } }),
    )

    const caught = await fetchResult(resultToken).catch((error: unknown) => error)

    expect(caught).toBeInstanceOf(ResultError)
    const failure = caught as InstanceType<typeof ResultError>
    expect(failure.is(code)).toBe(true)
    expect(failure.status).toBe(status)
    expect(failure.message).toBe('안내 문구')
  })

  it('모르는 코드도 접지 않고 그대로 싣는다', () => {
    const failure = normalizeResultError(
      axiosFailure(500, { success: false, data: null, error: { code: 'NEW_CODE', message: 'x' } }),
    )

    expect(failure.code).toBe('NEW_CODE')
  })

  it('200으로 온 실패 봉투도 오류로 다룬다', async () => {
    clientMocks.get.mockResolvedValue({
      data: { success: false, data: null, error: { code: 'RESULT_EXPIRED', message: '만료' } },
    })

    await expect(fetchResult(resultToken)).rejects.toMatchObject({ code: 'RESULT_EXPIRED' })
  })

  it('다시 시도는 CSRF 보호 mutation을 거치고 한도 오류의 대기 시간을 싣는다', async () => {
    const accepted = {
      publicToken: 'b41e0c9a7d2f35618ce4a09b7f1d2c63',
      status: 'QUEUED',
      resultSource: 'NEW',
      retryOfToken: resultToken,
    }
    clientMocks.post.mockResolvedValueOnce(ok(accepted))

    await expect(retryResult(resultToken)).resolves.toEqual(accepted)
    expect(clientMocks.runCsrfProtectedMutation).toHaveBeenCalledTimes(1)
    expect(clientMocks.post).toHaveBeenCalledWith(`/results/${resultToken}/retry`, null)

    // 공용 mutation은 오류를 ApiClientError로 감싸 던진다. 상태 코드와 details는 원래 오류에서 읽는다.
    const limited = axiosFailure(429, {
      success: false,
      data: null,
      error: { code: 'EXECUTION_LIMIT', message: '한도', details: { retryAfterSeconds: 30 } },
    })
    clientMocks.runCsrfProtectedMutation.mockRejectedValueOnce(
      new ApiClientError('api', '한도', { code: 'EXECUTION_LIMIT', cause: limited }),
    )

    await expect(retryResult(resultToken)).rejects.toMatchObject({
      code: 'EXECUTION_LIMIT',
      status: 429,
      retryAfterSeconds: 30,
    })
  })

  it('응답이 없으면 연결 실패 문구로 바꾼다', () => {
    const failure = normalizeResultError(new AxiosError('Network Error'))

    expect(failure.code).toBeNull()
    expect(failure.message).toContain('서버에 연결할 수 없습니다')
  })
})
