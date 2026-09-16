import { AxiosError, AxiosHeaders, CanceledError, type AxiosResponse } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LearningTermEntry } from '@/types/learning'
import { apiClient } from './client'
import { fetchGlossaryEntry } from './learningGlossary'

/**
 * 도움말 조회는 예외를 던지지 않고 세 상태로 가른다(013 term-help-ui §1, learning-public-api §3).
 */

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
  const error = new AxiosError('request failed')
  error.response = axiosResponse(body, status) as AxiosResponse
  return error
}

const entry: LearningTermEntry = {
  termId: 'synthetic-term',
  label: '합성 용어',
  sentence: '합성 문장이다.',
  articleId: 'synthetic-article',
  articleTitle: '합성 글',
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('fetchGlossaryEntry', () => {
  it('식별자로 조회하고 설명을 돌려준다', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(entry))

    await expect(fetchGlossaryEntry('synthetic-term')).resolves.toEqual({ status: 'ready', entry })
    expect(get).toHaveBeenCalledWith('/learning/terms/synthetic-term', { signal: undefined })
  })

  it('공통 봉투에 싸인 응답도 읽는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: entry, error: null }),
    )
    await expect(fetchGlossaryEntry('synthetic-term')).resolves.toEqual({ status: 'ready', entry })
  })

  it('404 LEARNING_TERM_NOT_READY만 설명 준비 중이다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(404, { code: 'LEARNING_TERM_NOT_READY', message: '설명을 준비하고 있습니다.' }),
    )
    await expect(fetchGlossaryEntry('synthetic-term')).resolves.toEqual({ status: 'notReady' })
  })

  it.each([
    ['코드 없는 404', axiosFailure(404, {})],
    ['서버 오류', axiosFailure(500, { code: 'LEARNING_TERM_NOT_READY' })],
    ['연결 실패', new AxiosError('network')],
    ['취소', new CanceledError()],
  ])('%s는 불러오지 못함이다', async (_, error) => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(error)
    await expect(fetchGlossaryEntry('synthetic-term')).resolves.toEqual({ status: 'failed' })
  })

  it('모양이 계약과 다른 응답을 빈 글로 채우지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse({ termId: 'synthetic-term' }))
    await expect(fetchGlossaryEntry('synthetic-term')).resolves.toEqual({ status: 'failed' })
  })
})
