import { AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(response({ distance: '0.412938571034' }))
    const query = { mode: 'field' as const, field: 'test-field', limit: 3 as const }
    const search = await getRegimeSearch(query)
    await getRegimeSearchById('FIELD:one/two', 10)
    await getRegimeCaseDetail('2012-03', { searchId: 'FIELD:one/two' })
    await getRegimeCandidates({ searchId: 'FIELD:one/two' })
    await getRegimeBaselineMonths()

    expect(search).toHaveProperty('distance', '0.412938571034')
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
})
