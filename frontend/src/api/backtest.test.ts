import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BacktestRejectionCode, readBacktestRejection } from '@/components/strategy/analysisSupport'
import type { BacktestCreateRequest } from '@/types/backtestRequest'
import { BacktestRequestError, createBacktest } from './backtest'
import { apiClient, resetCsrfToken } from './client'

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return {
    data,
    status,
    statusText: status >= 400 ? 'Error' : 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function axiosFailure(status: number, body: unknown): AxiosError {
  const error = new AxiosError('request failed', 'ERR_BAD_REQUEST')
  error.response = axiosResponse(body, status) as AxiosResponse
  return error
}

const request: BacktestCreateRequest = {
  executionType: 'SINGLE',
  sectorAllocation: {
    mappingVersion: 'SEM-TEST-1',
    items: [
      { sectorId: 'ALPHA', instrumentId: 'ALPHA.XKRX' },
      { sectorId: 'BETA', instrumentId: 'BETA.XKRX' },
    ],
  },
  strategy: {
    strategyId: 'DRIFT_BALANCE',
    rulesVersion: 'RULES-TEST-1',
    registryVersion: 'TEST-REGISTRY-003',
    parameters: { driftWindow: 45 },
  },
  conditions: {
    start: '2020-01-02',
    end: '2024-12-30',
    initialCash: '20000000.00',
    costProfileVersion: null,
    warmupTradingDays: 45,
    scenario: 'FULL_PERIOD',
  },
}

function errorBody(code: string, message: string, details?: Record<string, unknown>) {
  return { success: false, data: null, error: { code, message, ...(details ? { details } : {}) } }
}

describe('분석 요청 전송(T039)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse({}))
  })

  it('CSRF 토큰을 준비한 뒤 조립한 요청을 그대로 POST /backtests로 보내고 접수 결과를 읽는다', async () => {
    const postSpy = vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse(
        {
          success: true,
          data: { backtestId: 'BT-20260917-000001', status: 'QUEUED', resultSource: 'NEW', publicToken: 'x' },
          error: null,
        },
        202,
      ),
    )

    const accepted = await createBacktest(request)

    expect(apiClient.get).toHaveBeenCalledWith('/auth/csrf')
    expect(postSpy).toHaveBeenCalledWith('/backtests', request)
    expect(accepted).toEqual({ backtestId: 'BT-20260917-000001', status: 'QUEUED', resultSource: 'NEW' })
  })

  it('거절되면 서버의 오류 이름과 details를 실어 던지고, 화면의 판별기가 그 둘을 읽는다', async () => {
    const details = { axis: 'driftWindow', value: 999, allowedRange: { values: [15, 45, 90] } }
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        400,
        errorBody('UNREGISTERED_PARAMETER_VALUE', '허용 범위를 벗어난 값입니다: 항목=driftWindow', details),
      ),
    )

    const error = await createBacktest(request).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(BacktestRequestError)
    expect(error).toMatchObject({ kind: 'api', status: 400, code: 'UNREGISTERED_PARAMETER_VALUE' })
    expect(readBacktestRejection(error)).toEqual({
      code: BacktestRejectionCode.UNREGISTERED_PARAMETER_VALUE,
      details,
    })
  })

  it.each([
    [409, 'REGISTRY_VERSION_CHANGED'],
    [422, 'STRATEGY_NOT_REGISTERED'],
    [422, 'RULES_NOT_REGISTERED'],
    [503, 'REGISTRY_UNAVAILABLE'],
  ])('%i %s는 전송 계층 이름이 아니라 서버 이름을 code에 싣고 details가 없으면 null이다', async (status, code) => {
    vi.spyOn(apiClient, 'post').mockRejectedValue(axiosFailure(status, errorBody(code, '서버 문장')))

    const error = await createBacktest(request).catch((caught: unknown) => caught)

    expect(error).toMatchObject({ code, status, message: '서버 문장', details: null })
  })

  it('다시 보내지 않는다', async () => {
    const postSpy = vi
      .spyOn(apiClient, 'post')
      .mockRejectedValue(axiosFailure(503, errorBody('REGISTRY_UNAVAILABLE', '잠시 뒤')))

    await expect(createBacktest(request)).rejects.toBeInstanceOf(BacktestRequestError)
    expect(postSpy).toHaveBeenCalledTimes(1)
  })

  it('서버에 닿지 못하면 network로 던진다', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))

    await expect(createBacktest(request)).rejects.toMatchObject({ kind: 'network', code: null })
  })

  it('202인데 접수 결과를 읽지 못하면 성공으로 넘기지 않는다', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse({ success: true, data: { status: 'QUEUED' }, error: null }, 202),
    )

    await expect(createBacktest(request)).rejects.toMatchObject({ kind: 'unexpected' })
  })

  it('CSRF 토큰이 무효라는 거절이면 다음 요청에서 토큰을 새로 받는다', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(403, errorBody('CSRF_TOKEN_INVALID', '토큰이 무효입니다.')),
    )

    await expect(createBacktest(request)).rejects.toMatchObject({ code: 'CSRF_TOKEN_INVALID' })
    await expect(createBacktest(request)).rejects.toMatchObject({ code: 'CSRF_TOKEN_INVALID' })

    expect(apiClient.get).toHaveBeenCalledTimes(2)
  })
})
