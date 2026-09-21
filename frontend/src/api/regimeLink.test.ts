import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  createRegimeLinkSearch,
  createRegimeLinks,
  fetchAvailability,
  fetchCaseWindow,
  fetchRegimeLink,
  listRegimeLinks,
  normalizeRegimeLinkError,
  retryRegimeLink,
  RegimeLinkError,
} from './regimeLink'

function response(data: unknown, status = 200): AxiosResponse {
  return {
    data: { success: true, data, error: null },
    status,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function failure(status: number, code: string, message: string): AxiosError {
  const error = new AxiosError(message)
  error.response = {
    data: { success: false, data: null, error: { code, message } },
    status,
    statusText: 'ERR',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
  return error
}

const caseWindow = {
  basis: { kind: 'HISTORICAL_REVISION' },
  caseId: 'FIELD:LABOR:2026-07-01:MACRO-1:REG-3:REGIME-V1~2012-03',
  endMonth: '2012-03',
  postWindow: {
    d0: '2012-04-02',
    d21: '2012-05-02',
    d63: '2012-07-03',
    lastAvailable: '2012-07-03',
    completedTradingDays: 63,
    complete21: true,
    complete63: true,
  },
}

describe('사례 연결 API 통로', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('조회 경로를 공용 클라이언트로 부르고 질의를 그대로 싣는다', async () => {
    const get = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValueOnce(response({ basis: { kind: 'HISTORICAL_REVISION' }, cases: [] }))
      .mockResolvedValueOnce(response(caseWindow))
      .mockResolvedValueOnce(response({ linkId: 'RL-1', status: 'QUEUED' }))
      .mockResolvedValueOnce(response([]))

    await fetchAvailability({ base: '9f2c', entryPath: 'MARKET_FLOW' })
    const window = await fetchCaseWindow(caseWindow.caseId)
    await fetchRegimeLink('RL-1', '9f2c')
    await listRegimeLinks({ base: '9f2c', caseId: caseWindow.caseId })

    expect(get).toHaveBeenNthCalledWith(1, '/regime-links/availability', {
      params: { base: '9f2c', entryPath: 'MARKET_FLOW' },
      signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(2, '/regime-links/case-window', {
      params: { caseId: caseWindow.caseId },
      signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(3, '/regime-links/RL-1', {
      params: { token: '9f2c' },
      signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(4, '/regime-links', {
      params: { base: '9f2c', caseId: caseWindow.caseId },
      signal: undefined,
    })
    // 003이 종료일 하한을 잡는 값이다. §1a에만 있으므로 여기서 사라지면 제약이 조용히 풀린다.
    expect(window.postWindow.lastAvailable).toBe('2012-07-03')
  })

  it('쓰기 경로는 본문을 그대로 보낸다', async () => {
    const post = vi
      .spyOn(apiClient, 'post')
      .mockResolvedValueOnce(response({ linkSearchId: 'RLS-1', cases: [] }, 201))
      .mockResolvedValueOnce(response({ linkSearchId: 'RLS-1', links: [] }, 202))
      .mockResolvedValueOnce(response({ linkId: 'RL-1', status: 'QUEUED' }, 202))

    const search = { clientRequestId: 'c-1', snapshotKey: 'FIELD:LABOR', base: '9f2c', entryPath: 'MARKET_FLOW' as const, fullSearchShown: false }
    await createRegimeLinkSearch(search)
    const create = { linkSearchId: 'RLS-1', caseId: caseWindow.caseId, scenario: 'CONTINUOUS' as const, confirmFullPeriod: false }
    await createRegimeLinks(create)
    await retryRegimeLink('RL-1')

    expect(post).toHaveBeenNthCalledWith(1, '/regime-link-searches', search)
    expect(post).toHaveBeenNthCalledWith(2, '/regime-links', create)
    expect(post).toHaveBeenNthCalledWith(3, '/regime-links/RL-1/retry', null)
  })

  it('확인이 필요한 200 응답을 접수와 같은 자리로 돌려준다', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue(
      response({
        outcome: 'OUT_OF_PERIOD_CONFIRM_REQUIRED',
        requiredRange: { start: '2006-10-02', end: '2025-12-30' },
        affected: [],
      }),
    )

    const outcome = await createRegimeLinks({
      linkSearchId: 'RLS-1',
      caseId: caseWindow.caseId,
      scenario: 'CONTINUOUS',
      confirmFullPeriod: false,
    })

    expect(outcome.outcome).toBe('OUT_OF_PERIOD_CONFIRM_REQUIRED')
  })

  it('서버 오류 이름과 상태 코드를 잃지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(failure(404, 'RESOURCE_NOT_FOUND', '사례를 찾을 수 없습니다.'))

    await expect(fetchCaseWindow('없는-사례~2012-03')).rejects.toMatchObject({
      code: 'RESOURCE_NOT_FOUND',
      status: 404,
      message: '사례를 찾을 수 없습니다.',
    })
  })

  it('success가 false인 200 응답을 빈 결과로 넘기지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { success: false, data: null, error: { code: 'INVALID_INPUT', message: '형식이 틀렸습니다.' } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: { headers: new AxiosHeaders() },
    } as AxiosResponse)

    await expect(fetchCaseWindow('bad')).rejects.toBeInstanceOf(RegimeLinkError)
  })

  it('응답 없는 오류는 연결 문제로 말한다', () => {
    expect(normalizeRegimeLinkError(new AxiosError('네트워크')).code).toBeNull()
  })
})
