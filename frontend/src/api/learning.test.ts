import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import type { LearningArticle, LearningArticleList } from '@/types/learning'
import { articleSummaries, harborArticle, tideArticle } from '@/test/learningFixtures'
import { apiClient } from './client'
import { fetchLearningArticle, fetchLearningArticles } from './learning'

/** 013 T026. 빈 목록·볼 수 없는 글·실패를 이 층에서 가르는지 본다(quickstart 4-2). */

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

function success<T>(data: T): ApiSuccessEnvelope<T> {
  return { success: true, data, error: null }
}

function errorEnvelope(code: string, message: string): ApiErrorEnvelope {
  return { success: false, data: null, error: { code, message } }
}

describe('배우기 글 목록 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('GET /learning/articles를 한 번 부르고 글이 있으면 받은 순서 그대로 loaded다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse(success<LearningArticleList>({ articles: articleSummaries })))

    const result = await fetchLearningArticles()

    expect(getSpy).toHaveBeenCalledTimes(1)
    expect(getSpy.mock.calls[0][0]).toBe('/learning/articles')
    expect(result).toEqual({ status: 'loaded', articles: articleSummaries })
  })

  it('빈 배열이면 실패가 아니라 empty다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success({ articles: [] })))

    await expect(fetchLearningArticles()).resolves.toEqual({ status: 'empty' })
  })

  it('5xx는 서버 코드와 문장을 그대로 담은 failed다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(500, errorEnvelope('INTERNAL_ERROR', '서버 오류입니다.')),
    )

    await expect(fetchLearningArticles()).resolves.toEqual({
      status: 'failed',
      kind: 'api',
      code: 'INTERNAL_ERROR',
      message: '서버 오류입니다.',
    })
  })

  it('연결 실패는 reject되지 않고 network failed다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new AxiosError('Network Error'))

    const result = await fetchLearningArticles()

    expect(result).toMatchObject({ status: 'failed', kind: 'network', code: null })
  })

  it('계약과 다른 모양은 빈 목록으로 접지 않고 failed다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success({ items: [] })))

    await expect(fetchLearningArticles()).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })
})

describe('배우기 글 하나 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('articleId를 경로에 넣어 한 번 부르고 loaded다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse(success<LearningArticle>(tideArticle)))

    const result = await fetchLearningArticle('tide-reading')

    expect(getSpy).toHaveBeenCalledTimes(1)
    expect(getSpy.mock.calls[0][0]).toBe('/learning/articles/tide-reading')
    expect(result).toEqual({ status: 'loaded', article: tideArticle })
  })

  it('예시를 넘길 수 없는 글도 200이면 loaded다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(harborArticle)))

    await expect(fetchLearningArticle('harbor-notes')).resolves.toEqual({
      status: 'loaded',
      article: harborArticle,
    })
  })

  it('출처의 url 키가 없으면 null로 받는다', async () => {
    const withoutUrl = { ...tideArticle, sources: [{ label: '주소 없음' }] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(withoutUrl)))

    const result = await fetchLearningArticle('tide-reading')

    expect(result).toMatchObject({
      status: 'loaded',
      article: { sources: [{ label: '주소 없음', url: null }] },
    })
  })

  it('404 LEARNING_ARTICLE_UNAVAILABLE은 unavailable이다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(
        404,
        errorEnvelope('LEARNING_ARTICLE_UNAVAILABLE', '지금은 볼 수 없는 글입니다.'),
      ),
    )

    await expect(fetchLearningArticle('draft-only')).resolves.toEqual({ status: 'unavailable' })
  })

  it('다른 404 코드는 unavailable로 접지 않고 failed다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(404, errorEnvelope('RESOURCE_NOT_FOUND', '찾을 수 없습니다.')),
    )

    await expect(fetchLearningArticle('tide-reading')).resolves.toEqual({
      status: 'failed',
      kind: 'api',
      code: 'RESOURCE_NOT_FOUND',
      message: '찾을 수 없습니다.',
    })
  })

  it('5xx와 연결 실패는 reject되지 않고 failed다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get')
    getSpy.mockRejectedValueOnce(axiosFailure(502, '<html>bad gateway</html>'))
    getSpy.mockRejectedValueOnce(new AxiosError('Network Error'))

    await expect(fetchLearningArticle('tide-reading')).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
    await expect(fetchLearningArticle('tide-reading')).resolves.toMatchObject({
      status: 'failed',
      kind: 'network',
    })
  })

  it('아는 블록 종류의 필드가 빠지면 빈 글로 채우지 않고 failed다', async () => {
    const broken = { ...tideArticle, blocks: [{ type: 'heading' }] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(broken)))

    await expect(fetchLearningArticle('tide-reading')).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('모르는 블록 종류는 글 전체를 실패로 만들지 않는다', async () => {
    const future = { ...tideArticle, blocks: [{ type: 'chart', data: [] }, ...tideArticle.blocks] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(future)))

    const result = await fetchLearningArticle('tide-reading')

    expect(result.status).toBe('loaded')
  })
})
