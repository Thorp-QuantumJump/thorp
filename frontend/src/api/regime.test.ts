import { AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { regimeResult } from '@/test/regimeFixtures'
import { apiClient } from './client'
import {
  getRegimeBaselineMonths,
  getRegimeCandidates,
  getRegimeCaseDetail,
  getRegimeSearch,
  getRegimeSearchById,
} from './regime'

function response(result: unknown): AxiosResponse {
  return {
    data: { httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200, result },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

describe('경제환경 검색 API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('다섯 조회 경로를 공용 클라이언트로 호출하고 decimal 문자열을 보존한다', async () => {
    const get = vi.spyOn(apiClient, 'get')
      .mockResolvedValueOnce(response(regimeResult()))
      .mockResolvedValueOnce(response(regimeResult()))
      .mockResolvedValueOnce(response({ distance: '0.412938571034' }))
      .mockResolvedValueOnce(response({ candidates: [] }))
      .mockResolvedValueOnce(response({ byField: [{ id: 'field-1', name: '시험 분야' }] }))
    const query = { mode: 'field' as const, field: 'test-field', limit: 3 as const }
    const search = await getRegimeSearch(query)
    await getRegimeSearchById('FIELD:one/two', 10)
    await getRegimeCaseDetail('2012-03', { searchId: 'FIELD:one/two' })
    await getRegimeCandidates({ searchId: 'FIELD:one/two' })
    await getRegimeBaselineMonths()

    expect(search.cases[0].distance).toBe('0.412938571034')
    expect(get).toHaveBeenNthCalledWith(1, '/regime-searches', { params: query, signal: undefined })
    expect(get).toHaveBeenNthCalledWith(2, '/regime-searches/FIELD%3Aone%2Ftwo', {
      params: { limit: 10 }, signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(3, '/regime-searches/cases/2012-03', {
      params: { searchId: 'FIELD:one/two' }, signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(4, '/regime-searches/candidates', {
      params: { searchId: 'FIELD:one/two' }, signal: undefined,
    })
    expect(get).toHaveBeenNthCalledWith(5, '/regime-searches/baseline-months', {
      signal: undefined,
    })
  })

  it('실패를 성공 결과로 바꾸지 않고 호출자에게 올린다', async () => {
    const failure = new Error('server failure')
    vi.spyOn(apiClient, 'get').mockRejectedValue(failure)
    await expect(getRegimeSearch({ mode: 'field', field: 'test-field' })).rejects.toBe(failure)
  })

  it('성공 봉투가 어긋나면 빈 결과로 취급하지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({
      ...response({ cases: [] }),
      data: { httpStatus: 'SERVICE_UNAVAILABLE', isSuccess: false, message: '조회 실패', code: 503, result: { cases: [] } },
    })
    await expect(getRegimeSearch({ mode: 'field', field: 'test-field' })).rejects.toThrow()
  })

  it('빠진 달의 안내가 없는 200 응답을 실패로 올린다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(regimeResult({
      comparisonMonths: [{ indicatorId: 'one', indicator: '시험 지표', months: [], omitted: ['2026-06'] }],
    })))
    await expect(getRegimeSearch({ mode: 'field', field: 'test-field' })).rejects.toThrow('계약과 다릅니다')
  })
})
