import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminTideArticle, publishCheckFailures } from '@/test/learningFixtures'
import { apiClient } from './client'
import {
  checkAdminAccess,
  fetchAdminArticles,
  publishArticle,
  saveDraft,
  unpublishArticle,
} from './learningAdmin'

/**
 * 관리 API는 예외를 던지지 않고 상태로 가른다(013 contracts/learning-admin-api.md, T069).
 *
 * 여기서 확인하는 것은 <b>서버가 준 것을 그대로 옮기는가</b>다. 검사 항목의 문장을 바꾸거나
 * `details`를 잃으면 화면이 무엇을 고쳐야 하는지 말할 수 없게 된다(research R7·R12).
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

function envelopeError(code: string, message: string, details?: unknown) {
  return { success: false, data: null, error: { code, message, details } }
}

/** 상태 변경 요청은 `runCsrfProtectedMutation`이 앞서 토큰을 준비한다. 그 요청까지 막아 둔다. */
function stubCsrfPreparation() {
  return vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse({ success: true, data: null }))
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=synthetic-csrf-token'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('checkAdminAccess', () => {
  it('200이면 관리자다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse({ admin: true }))
    await expect(checkAdminAccess()).resolves.toEqual({ status: 'admin' })
  })

  it('401은 로그인이 풀린 것이고 404는 권한이 없는 것이다', async () => {
    const get = vi.spyOn(apiClient, 'get')

    get.mockRejectedValueOnce(
      axiosFailure(401, envelopeError('AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')),
    )
    await expect(checkAdminAccess()).resolves.toEqual({ status: 'unauthenticated' })

    get.mockRejectedValueOnce(
      axiosFailure(404, envelopeError('RESOURCE_NOT_FOUND', '찾을 수 없습니다.')),
    )
    await expect(checkAdminAccess()).resolves.toEqual({ status: 'denied' })
  })
})

describe('fetchAdminArticles', () => {
  it('404는 권한 없음이고 그 밖의 실패는 failed다', async () => {
    const get = vi.spyOn(apiClient, 'get')

    get.mockRejectedValueOnce(
      axiosFailure(404, envelopeError('RESOURCE_NOT_FOUND', '찾을 수 없습니다.')),
    )
    await expect(fetchAdminArticles()).resolves.toEqual({ status: 'denied' })

    get.mockRejectedValueOnce(
      axiosFailure(500, envelopeError('INTERNAL_ERROR', '서버 오류입니다.')),
    )
    await expect(fetchAdminArticles()).resolves.toMatchObject({
      status: 'failed',
      code: 'INTERNAL_ERROR',
    })
  })

  it('needsUpdate의 null을 false로 접지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          articles: [
            {
              articleId: 'tide-reading',
              title: '물때 읽기',
              readingOrder: 2,
              status: 'DRAFT',
              draftVersion: 0,
              hasUnpublishedChanges: false,
              needsUpdate: null,
              lastPublication: null,
            },
          ],
        },
        error: null,
      }),
    )

    const result = await fetchAdminArticles()
    expect(result.status).toBe('loaded')
    if (result.status !== 'loaded') return
    expect(result.articles[0].needsUpdate).toBeNull()
  })
})

describe('saveDraft', () => {
  const request = { ...adminTideArticle.draft, expectedDraftVersion: 5 }

  it('409 LEARNING_DRAFT_CHANGED의 currentDraftVersion을 details에서 읽는다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'put').mockRejectedValue(
      axiosFailure(
        409,
        envelopeError('LEARNING_DRAFT_CHANGED', '초안이 바뀌었습니다.', {
          currentDraftVersion: 9,
        }),
      ),
    )

    await expect(saveDraft('tide-reading', request)).resolves.toEqual({
      status: 'draftChanged',
      message: '초안이 바뀌었습니다.',
      currentDraftVersion: 9,
    })
  })

  it('409 LEARNING_TERM_ID_TAKEN은 termId와 ownerArticleId를 함께 옮긴다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'put').mockRejectedValue(
      axiosFailure(
        409,
        envelopeError('LEARNING_TERM_ID_TAKEN', '다른 글이 쓰는 용어입니다.', {
          termId: 'ebb-gap',
          ownerArticleId: 'harbor-notes',
        }),
      ),
    )

    await expect(saveDraft('tide-reading', request)).resolves.toEqual({
      status: 'termIdTaken',
      message: '다른 글이 쓰는 용어입니다.',
      termId: 'ebb-gap',
      ownerArticleId: 'harbor-notes',
    })
  })

  it('400은 details.fields를 그대로 싣는다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'put').mockRejectedValue(
      axiosFailure(
        400,
        envelopeError('INVALID_INPUT', 'blocks[0].text: 길이를 넘었습니다', {
          fields: [{ field: 'blocks[0].text', message: '길이를 넘었습니다' }],
        }),
      ),
    )

    await expect(saveDraft('tide-reading', request)).resolves.toEqual({
      status: 'invalid',
      message: 'blocks[0].text: 길이를 넘었습니다',
      fields: [{ field: 'blocks[0].text', message: '길이를 넘었습니다' }],
    })
  })

  it('401은 unauthenticated다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'put').mockRejectedValue(
      axiosFailure(401, envelopeError('AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')),
    )

    await expect(saveDraft('tide-reading', request)).resolves.toEqual({
      status: 'unauthenticated',
    })
  })

  it('상태 변경 요청에 X-XSRF-TOKEN이 실린다', async () => {
    stubCsrfPreparation()
    const put = vi
      .spyOn(apiClient, 'put')
      .mockResolvedValue(axiosResponse({ success: true, data: adminTideArticle, error: null }))

    await saveDraft('tide-reading', request)

    expect(put).toHaveBeenCalledTimes(1)
    // 헤더는 axios 인스턴스 설정(`xsrfCookieName`·`xsrfHeaderName`)이 쿠키에서 붙인다.
    expect(apiClient.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
    expect(document.cookie).toContain('XSRF-TOKEN=synthetic-csrf-token')
  })
})

describe('publishArticle', () => {
  it('422는 failures를 서버 배열 그대로 옮기고 message를 바꾸지 않는다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        422,
        envelopeError('LEARNING_PUBLISH_CHECK_FAILED', '게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.', {
          checkVersion: 'learning-publish-checks-v1',
          failures: publishCheckFailures,
        }),
      ),
    )

    const result = await publishArticle('tide-reading', 5)
    expect(result.status).toBe('checkFailed')
    if (result.status !== 'checkFailed') return
    expect(result.checkVersion).toBe('learning-publish-checks-v1')
    expect(result.failures).toEqual(publishCheckFailures)
  })

  it('게시도 409 LEARNING_DRAFT_CHANGED를 같은 갈래로 돌려준다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        409,
        envelopeError('LEARNING_DRAFT_CHANGED', '초안이 바뀌었습니다.', {
          currentDraftVersion: 6,
        }),
      ),
    )

    await expect(publishArticle('tide-reading', 5)).resolves.toEqual({
      status: 'draftChanged',
      message: '초안이 바뀌었습니다.',
      currentDraftVersion: 6,
    })
  })

  it('200은 판 번호와 검사 버전을 그대로 읽는다', async () => {
    stubCsrfPreparation()
    const published = {
      articleId: 'tide-reading',
      status: 'PUBLISHED',
      revisionNo: 4,
      checkVersion: 'learning-publish-checks-v1',
      sectorMapVersion: 'SEM-2026-09-10-001',
      publishedAt: '2026-09-21T02:00:00Z',
    }
    vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse({ success: true, data: published, error: null }),
    )

    await expect(publishArticle('tide-reading', 5)).resolves.toEqual({
      status: 'published',
      result: published,
    })
  })
})

describe('unpublishArticle', () => {
  it('이미 초안이면 unpublishedRevisionNo가 null인 200이다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse({
        success: true,
        data: { articleId: 'moor-lines', status: 'DRAFT', unpublishedRevisionNo: null },
        error: null,
      }),
    )

    await expect(unpublishArticle('moor-lines')).resolves.toEqual({
      status: 'unpublished',
      result: { articleId: 'moor-lines', status: 'DRAFT', unpublishedRevisionNo: null },
    })
  })
})
