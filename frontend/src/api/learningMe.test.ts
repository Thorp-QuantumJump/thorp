import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import {
  answerResult,
  completedRecord,
  completionResult,
  retryResult,
} from '@/test/learningMeFixtures'
import { apiClient, resetCsrfToken } from './client'
import {
  answerQuestion,
  completeArticle,
  fetchLearnerArticle,
  fetchLearningMe,
  recordPosition,
  resetLearning,
  retryAnswer,
  sendPositionOnLeave,
} from './learningMe'

/**
 * 013 T185·T190. 나의 학습 통로가 실패를 화면이 가를 이름으로 나누고, 탭을 닫을 때의 위치 기록이 백엔드 주소와
 * CSRF 헤더를 싣는지 본다(learning-me-api §0·§3, research R32, quickstart 10-2·10-3).
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

function success<T>(data: T): ApiSuccessEnvelope<T> {
  return { success: true, data, error: null }
}

function errorEnvelope(code: string, message: string): ApiErrorEnvelope {
  return { success: false, data: null, error: { code, message } }
}

/** 서버 통합 테스트가 실제 응답으로 만든 계약 예시(`docs/specs/learning-api/examples`). */
const examplesDir = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  '..',
  'docs',
  'specs',
  'learning-api',
  'examples',
)

function readExample(name: string): unknown {
  return JSON.parse(readFileSync(resolve(examplesDir, name), 'utf8'))
}

describe('나의 학습 통로', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
    // 상태 변경 전 CSRF 준비 요청(`GET /auth/csrf`)은 성공으로 둔다.
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(null)))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('글 기록은 GET /learning/me/articles/{id}로 받고 계약 모양 그대로 돌려준다', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(completedRecord)))

    const result = await fetchLearnerArticle('swell reading')

    expect(get.mock.calls[0][0]).toBe('/learning/me/articles/swell%20reading')
    expect(result).toEqual({ status: 'ok', data: completedRecord })
  })

  it('401은 unauthenticated, 409 LEARNING_NOT_RECORDABLE은 notRecordable로 가른다', async () => {
    const get = vi.spyOn(apiClient, 'get')
    get.mockRejectedValueOnce(
      axiosFailure(401, errorEnvelope('AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')),
    )
    expect(await fetchLearnerArticle('swell-reading')).toEqual({ status: 'unauthenticated' })

    get.mockRejectedValueOnce(
      axiosFailure(409, errorEnvelope('LEARNING_NOT_RECORDABLE', '기록하지 않는 글입니다.')),
    )
    expect(await fetchLearnerArticle('swell-reading')).toEqual({ status: 'notRecordable' })
  })

  it('다른 곳에서 글이나 기록이 바뀐 실패는 다시 보내도 같으므로 stale로 가른다', async () => {
    const post = vi.spyOn(apiClient, 'post')
    post.mockRejectedValueOnce(
      axiosFailure(404, errorEnvelope('LEARNING_ARTICLE_UNAVAILABLE', '볼 수 없는 글입니다.')),
    )
    expect(await completeArticle('swell-reading')).toEqual({ status: 'stale' })

    post.mockRejectedValueOnce(
      axiosFailure(409, errorEnvelope('LEARNING_COMPLETION_REQUIRED', '먼저 완료해야 합니다.')),
    )
    expect(await answerQuestion('swell-reading', 'q1', 'c2')).toEqual({ status: 'stale' })

    post.mockRejectedValueOnce(
      axiosFailure(409, errorEnvelope('LEARNING_RETRY_NOT_AVAILABLE', '다시 풀 수 없습니다.')),
    )
    expect(await retryAnswer('swell-reading', 'q1', 'c2')).toEqual({ status: 'stale' })
  })

  it('계약과 다른 모양은 빈 기록으로 채우지 않고 실패다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse(success({ ...completedRecord, state: 'DONE' })),
    )

    const result = await fetchLearnerArticle('swell-reading')

    expect(result.status).toBe('failed')
  })

  it('완료는 CSRF를 준비한 뒤 본문 없이 POST하고 받은 gained를 그대로 돌려준다', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(null)))
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue(axiosResponse(success(completionResult)))

    const result = await completeArticle('swell-reading')

    expect(get.mock.calls[0][0]).toBe('/auth/csrf')
    expect(get.mock.invocationCallOrder[0]).toBeLessThan(post.mock.invocationCallOrder[0])
    expect(post.mock.calls[0][0]).toBe('/learning/me/articles/swell-reading/completion')
    expect(post.mock.calls[0]).toHaveLength(1)
    expect(result).toEqual({ status: 'ok', data: completionResult })
  })

  it('CSRF 토큰이 어긋나면 다시 보내지 않고 토큰만 새로 받아 둔 뒤 실패로 돌려준다', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(null)))
    const post = vi
      .spyOn(apiClient, 'post')
      .mockRejectedValue(axiosFailure(403, errorEnvelope('CSRF_TOKEN_INVALID', '토큰 불일치')))

    const result = await answerQuestion('swell-reading', 'q1', 'c2')

    expect(result).toMatchObject({ status: 'failed', code: 'CSRF_TOKEN_INVALID' })
    expect(post).toHaveBeenCalledTimes(1)
    expect(get.mock.calls.map(([path]) => path)).toEqual(['/auth/csrf', '/auth/csrf'])
  })

  it('첫 답과 다시 풀기는 문제와 보기를 보내고, 문제가 바뀐 404는 questionNotFound다', async () => {
    const post = vi
      .spyOn(apiClient, 'post')
      .mockResolvedValueOnce(axiosResponse(success(answerResult())))
      .mockResolvedValueOnce(axiosResponse(success(retryResult())))
      .mockRejectedValueOnce(
        axiosFailure(404, errorEnvelope('LEARNING_QUESTION_NOT_FOUND', '문제가 없습니다.')),
      )

    expect(await answerQuestion('swell-reading', 'q1', 'c2')).toEqual({
      status: 'ok',
      data: answerResult(),
    })
    expect(post.mock.calls[0]).toEqual([
      '/learning/me/articles/swell-reading/answers',
      { questionId: 'q1', choiceId: 'c2' },
    ])
    expect(await retryAnswer('swell-reading', 'q1', 'c2')).toEqual({
      status: 'ok',
      data: retryResult(),
    })
    expect(post.mock.calls[1][0]).toBe('/learning/me/articles/swell-reading/retry-answers')
    expect(await answerQuestion('swell-reading', 'q9', 'c1')).toEqual({ status: 'questionNotFound' })
  })

  it('나의 학습 전체는 GET /learning/me이고 첫 단계 공개 전 409는 notRecordable이다', async () => {
    const get = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValueOnce(axiosResponse(success(readExample('me.json'))))
      .mockRejectedValueOnce(
        axiosFailure(409, errorEnvelope('LEARNING_NOT_RECORDABLE', '아직 켜지지 않았습니다.')),
      )

    const loaded = await fetchLearningMe()

    expect(get.mock.calls[0][0]).toBe('/learning/me')
    expect(loaded.status).toBe('ok')
    expect(await fetchLearningMe()).toEqual({ status: 'notRecordable' })
  })

  it('학습 초기화는 CSRF로 감싼 DELETE /learning/me이고 204면 ok다', async () => {
    const remove = vi.spyOn(apiClient, 'delete').mockResolvedValue(axiosResponse(null, 204))

    expect(await resetLearning()).toEqual({ status: 'ok', data: null })
    expect(remove.mock.calls[0][0]).toBe('/learning/me')
  })

  it('화면 안 이동의 위치 기록은 apiClient로 PUT하고 실패해도 던지지 않고 결과로 돌려준다', async () => {
    const put = vi.spyOn(apiClient, 'put').mockRejectedValue(new AxiosError('network'))

    const result = await recordPosition('swell-reading', 62)

    expect(result).toMatchObject({ status: 'failed', kind: 'network' })
    expect(put.mock.calls[0]).toEqual(['/learning/me/articles/swell-reading/position', { percent: 62 }])
  })

  it('탭을 닫을 때의 위치 기록은 apiClient 기준 주소를 붙인 keepalive PUT이고 JSON 본문과 X-XSRF-TOKEN을 싣는다', () => {
    document.cookie = 'XSRF-TOKEN=token%2Fvalue; path=/'
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    sendPositionOnLeave('swell-reading', 62)

    expect(apiClient.defaults.baseURL).toBe('/api')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(url).toBe(`${apiClient.defaults.baseURL}/learning/me/articles/swell-reading/position`)
    expect(init).toMatchObject({ method: 'PUT', keepalive: true, credentials: 'include' })
    expect(init?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': 'token/value',
    })
    expect(JSON.parse(String(init?.body))).toEqual({ percent: 62 })
  })
})

/**
 * 계약 예시를 읽기 함수로 그대로 통과시킨다. 서버가 실제로 보내는 값(값이 없는 필드의 null, 단계 완주가 아닌 경험치
 * 줄에서 빠지는 `stageNumber`)을 읽지 못해 실패로 떨어뜨리면 화면은 기록을 받지 못한 것으로 그린다.
 */
describe('나의 학습 통로 — 계약 예시', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
  })

  it.each([
    ['me.json', () => fetchLearningMe()],
    ['me-article.json', () => fetchLearnerArticle('etf-tracking-difference')],
  ])('%s를 읽는다', async (file, call) => {
    const example = readExample(file)
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(example)))

    expect(await call()).toEqual({ status: 'ok', data: example })
  })

  it.each([
    ['me-completion.json', () => completeArticle('etf-tracking-difference')],
    ['me-answer.json', () => answerQuestion('etf-tracking-difference', 'q2', 'c5')],
    ['me-retry.json', () => retryAnswer('etf-tracking-difference', 'q1', 'c3')],
  ])('%s를 읽는다', async (file, call) => {
    const example = readExample(file)
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(null)))
    vi.spyOn(apiClient, 'post').mockResolvedValue(axiosResponse(success(example)))

    expect(await call()).toEqual({ status: 'ok', data: example })
  })
})
