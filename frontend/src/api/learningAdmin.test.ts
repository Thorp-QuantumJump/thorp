import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  adminReadyReadiness,
  adminTideArticle,
  adminV2Article,
  publishCheckFailures,
} from '@/test/learningFixtures'
import { apiClient } from './client'
import {
  checkAdminAccess,
  fetchAdminArticle,
  fetchAdminArticles,
  fetchArticlePreview,
  fetchStageReadiness,
  publishArticle,
  releaseStage,
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
  it('404와 그 밖의 실패는 failed다. 권한 판정은 checkAdminAccess 한 곳이다', async () => {
    const get = vi.spyOn(apiClient, 'get')

    get.mockRejectedValueOnce(
      axiosFailure(404, envelopeError('RESOURCE_NOT_FOUND', '찾을 수 없습니다.')),
    )
    await expect(fetchAdminArticles()).resolves.toMatchObject({
      status: 'failed',
      code: 'RESOURCE_NOT_FOUND',
    })

    get.mockRejectedValueOnce(
      axiosFailure(500, envelopeError('INTERNAL_ERROR', '서버 오류입니다.')),
    )
    await expect(fetchAdminArticles()).resolves.toMatchObject({
      status: 'failed',
      code: 'INTERNAL_ERROR',
    })
  })

  it('needsUpdate·needsReview의 null을 false로 접지 않는다', async () => {
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
              format: 'V1',
              stage: { stageKey: 'shore-basics', number: 1, released: false },
              stagePosition: 2,
              publishedEdition: null,
              needsReview: null,
              pendingTransfers: 0,
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
    expect(result.articles[0].needsReview).toBeNull()
    expect(result.articles[0].stage).toEqual({
      stageKey: 'shore-basics',
      number: 1,
      released: false,
    })
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
})

/**
 * 상태 변경 요청에 쿠키의 CSRF 토큰이 헤더로 실리는가(013 T069·T092, 계약 §0).
 *
 * <p><b>`apiClient.put`이나 adapter를 대역으로 바꾸지 않는다.</b> axios는 헤더를 adapter 안의
 * `resolveConfig`에서 쿠키를 읽어 붙인다. 그 앞을 가로채면 헤더가 붙는 단계를 거치지 않아 이 검사가
 * 아무것도 확인하지 못한다. 그래서 실제 xhr adapter를 지나게 두고 `XMLHttpRequest`에서 헤더만
 * 받아 적은 뒤 보내기 직전에 멈춘다.
 */
describe('상태 변경 요청의 CSRF 헤더', () => {
  function captureRequestHeaders() {
    const setRequestHeader = vi.spyOn(XMLHttpRequest.prototype, 'setRequestHeader')
    vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => {
      throw new Error('테스트는 요청을 내보내지 않는다')
    })
    return () =>
      Object.fromEntries(setRequestHeader.mock.calls.map(([name, value]) => [name, value]))
  }

  it('초안 저장에 X-XSRF-TOKEN이 실린다', async () => {
    stubCsrfPreparation()
    const headers = captureRequestHeaders()

    await saveDraft('tide-reading', { ...adminTideArticle.draft, expectedDraftVersion: 5 })

    expect(headers()['X-XSRF-TOKEN']).toBe('synthetic-csrf-token')
  })

  it('게시에 X-XSRF-TOKEN이 실린다', async () => {
    stubCsrfPreparation()
    const headers = captureRequestHeaders()

    await publishArticle('tide-reading', 5)

    expect(headers()['X-XSRF-TOKEN']).toBe('synthetic-csrf-token')
  })

  it('단계 공개에 X-XSRF-TOKEN이 실린다', async () => {
    stubCsrfPreparation()
    const headers = captureRequestHeaders()

    await releaseStage('shore-basics', adminReadyReadiness.articles)

    expect(headers()['X-XSRF-TOKEN']).toBe('synthetic-csrf-token')
  })

  it('게시 취소에 X-XSRF-TOKEN이 실린다', async () => {
    stubCsrfPreparation()
    const headers = captureRequestHeaders()

    await unpublishArticle('tide-reading')

    expect(headers()['X-XSRF-TOKEN']).toBe('synthetic-csrf-token')
  })
})

describe('publishArticle', () => {
  it('422는 failures를 서버 배열 그대로 옮기고 message를 바꾸지 않는다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        422,
        envelopeError(
          'LEARNING_PUBLISH_CHECK_FAILED',
          '게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.',
          {
            checkVersion: 'learning-publish-checks-v1',
            failures: publishCheckFailures,
          },
        ),
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
      edition: 'CURRENT',
      renamedQuestions: [{ from: 'q4', to: 'q7' }],
      transferredTerms: [
        { termId: 'keel-room', fromArticleId: 'harbor-notes', toArticleId: 'tide-reading' },
      ],
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

describe('2판 초안 읽기', () => {
  it('V2 초안의 글자 조각·넘겨받기·확인 문제 식별자를 그대로 읽는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: adminV2Article, error: null }),
    )

    await expect(fetchAdminArticle('channel-depth')).resolves.toEqual({
      status: 'loaded',
      article: adminV2Article,
    })
  })

  it('형식을 모르는 초안은 읽지 못한 응답이다. 블록을 짐작해 읽지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: { ...adminV2Article, draft: { ...adminV2Article.draft, format: 'V3' } },
        error: null,
      }),
    )

    await expect(fetchAdminArticle('channel-depth')).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('미리 보기는 게시 시각이 null이어도 공개 글과 같은 해석기로 읽는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          articleId: 'channel-depth',
          edition: 'CURRENT',
          title: '물길 깊이 재기',
          question: '질문',
          readingOrder: 102,
          stage: { stageKey: 'shore-basics', number: 1, name: '물가 기초' },
          number: null,
          readingMinutes: 5,
          minDwellSeconds: 150,
          blocks: [{ type: 'heading', text: '소제목' }],
          quiz: [],
          example: null,
          sources: [],
          next: null,
          stageArticleCount: null,
          hasUnreleasedStages: true,
          publishedAt: null,
          preview: true,
        },
        error: null,
      }),
    )

    const result = await fetchArticlePreview('channel-depth')
    expect(result.status).toBe('loaded')
    if (result.status !== 'loaded') return
    expect(result.preview.article.edition).toBe('CURRENT')
    expect(result.preview.article.blocks).toEqual([{ type: 'heading', text: '소제목' }])
    expect(result.preview.incompleteQuestions).toEqual([])
  })

  it('고치는 중인 초안도 읽는다: 정답이 정해지지 않은 문제는 따로 두고, 예시 없는 V1 초안은 빈 예시다', async () => {
    // 백엔드 미리 보기 모드는 정답 보기가 하나가 아니면 correctChoiceId를, 예시 없는 V1 초안은 example을
    // null로 보낸다(ArticleViewAssembler). 둘 때문에 미리 보기 전체를 읽지 못하면 고치는 중인 초안을 볼 수 없다.
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          articleId: 'tide-reading',
          edition: 'PRE_RELEASE',
          title: '물때 읽기',
          question: '질문',
          readingOrder: 2,
          stage: { stageKey: 'shore-basics', number: 1, name: '물가 기초' },
          number: null,
          readingMinutes: 4,
          minDwellSeconds: null,
          blocks: [{ type: 'paragraph', text: '문단' }],
          quiz: [
            {
              questionId: 'q1',
              prompt: '정답을 아직 고르지 않은 문제',
              choices: [
                { choiceId: 'c2', text: '가' },
                { choiceId: 'c3', text: '나' },
              ],
              correctChoiceId: null,
              explanation: '',
            },
          ],
          example: null,
          sources: [],
          next: null,
          stageArticleCount: null,
          hasUnreleasedStages: true,
          publishedAt: null,
          preview: true,
        },
        error: null,
      }),
    )

    const result = await fetchArticlePreview('tide-reading')
    expect(result.status).toBe('loaded')
    if (result.status !== 'loaded') return
    expect(result.preview.article.example).toBeNull()
    expect(result.preview.article.quiz).toEqual([])
    expect(result.preview.incompleteQuestions).toEqual([
      { questionId: 'q1', prompt: '정답을 아직 고르지 않은 문제' },
    ])
  })
})

describe('2판 거절', () => {
  it('422의 quiz 위치와 field를 읽어 목록이 비지 않는다', async () => {
    stubCsrfPreparation()
    const failures = [
      {
        check: 'QUIZ',
        location: { part: 'quiz', questionId: 'q2', choiceId: 'c5' },
        message: '정답 보기가 하나가 아닙니다.',
      },
      {
        check: 'FORBIDDEN_EXPRESSION',
        location: { part: 'quiz', index: 1, field: 'explanation' },
        matched: '추천합니다',
        message: '추천으로 읽히는 표현이 있습니다.',
      },
      {
        check: 'RESULT_NUMBER_OUTSIDE_HYPOTHETICAL',
        location: { part: 'block', index: 4, field: 'rows[0][1]' },
        matched: '-30%',
        message: '실제 결과로 읽히는 숫자는 가상의 예 요소에만 쓸 수 있습니다.',
      },
    ]
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        422,
        envelopeError(
          'LEARNING_PUBLISH_CHECK_FAILED',
          '게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.',
          {
            checkVersion: 'learning-publish-checks-v2',
            failures,
          },
        ),
      ),
    )

    const result = await publishArticle('channel-depth', 4)
    expect(result.status).toBe('checkFailed')
    if (result.status !== 'checkFailed') return
    expect(result.failures).toEqual(failures)
  })

  it('넘겨받기 소유 글이 없으면 ownerArticleId가 null인 termTransferInvalid다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'put').mockRejectedValue(
      axiosFailure(
        409,
        envelopeError('LEARNING_TERM_TRANSFER_INVALID', '넘겨받을 수 없는 용어입니다.', {
          termId: 'keel-room',
        }),
      ),
    )

    await expect(
      saveDraft('channel-depth', { ...adminV2Article.draft, expectedDraftVersion: 4 }),
    ).resolves.toEqual({
      status: 'termTransferInvalid',
      message: '넘겨받을 수 없는 용어입니다.',
      termId: 'keel-room',
      ownerArticleId: null,
    })
  })

  it('공개 전 단계의 V2 게시는 stageNotReleased다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(409, envelopeError('LEARNING_STAGE_NOT_RELEASED', '단계 공개로만 게시합니다.')),
    )

    await expect(publishArticle('channel-depth', 4)).resolves.toEqual({
      status: 'stageNotReleased',
      message: '단계 공개로만 게시합니다.',
    })
  })
})

describe('단계 미리 검사와 공개', () => {
  it('미리 검사의 항목을 하나라도 읽지 못하면 통과로 접지 않고 읽지 못한 응답이다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          ...adminReadyReadiness,
          ready: false,
          articles: [
            {
              articleId: 'tide-reading',
              draftVersion: 5,
              failures: [{ check: 'FORMAT', location: { part: 'unknown-part' }, message: '형식' }],
            },
          ],
        },
        error: null,
      }),
    )

    await expect(fetchStageReadiness('shore-basics')).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('미리 검사의 article 위치(FORMAT·PLACEMENT)를 읽는다', async () => {
    const readiness = {
      ...adminReadyReadiness,
      ready: false,
      articles: [
        {
          articleId: 'tide-reading',
          draftVersion: 5,
          failures: [{ check: 'FORMAT', location: { part: 'article' }, message: 'V1 초안입니다.' }],
        },
      ],
    }
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: readiness, error: null }),
    )

    await expect(fetchStageReadiness('shore-basics')).resolves.toEqual({
      status: 'loaded',
      readiness,
    })
  })

  it('단계 공개는 미리 검사의 목록과 초안 번호를 expectedDraftVersion으로 싣는다', async () => {
    stubCsrfPreparation()
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          stageKey: 'shore-basics',
          releasedAt: '2026-11-19T01:00:00Z',
          published: [{ articleId: 'channel-depth', revisionNo: 3, renamedQuestions: [] }],
          transferredTerms: [],
          logEntryKey: 'stage:shore-basics',
          checkVersion: 'learning-publish-checks-v2',
        },
        error: null,
      }),
    )

    const result = await releaseStage('shore-basics', adminReadyReadiness.articles)

    expect(result.status).toBe('released')
    expect(post).toHaveBeenCalledWith('/admin/learning/stages/shore-basics/release', {
      articles: [
        { articleId: 'tide-reading', expectedDraftVersion: 5 },
        { articleId: 'channel-depth', expectedDraftVersion: 4 },
      ],
    })
  })

  it('단계 공개 422는 글마다 걸린 항목을 그대로 옮긴다', async () => {
    stubCsrfPreparation()
    const articles = [
      {
        articleId: 'channel-depth',
        failures: [
          {
            check: 'TERM_TRANSFER',
            location: { part: 'term', termId: 'keel-room' },
            message: '넘겨받기 소유 글이 다릅니다.',
          },
        ],
      },
    ]
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        422,
        envelopeError(
          'LEARNING_STAGE_CHECK_FAILED',
          '검사에 걸린 글이 있어 단계를 공개하지 않았습니다.',
          {
            checkVersion: 'learning-publish-checks-v2',
            articles,
          },
        ),
      ),
    )

    await expect(releaseStage('shore-basics', adminReadyReadiness.articles)).resolves.toEqual({
      status: 'checkFailed',
      message: '검사에 걸린 글이 있어 단계를 공개하지 않았습니다.',
      checkVersion: 'learning-publish-checks-v2',
      articles,
    })
  })

  it('409 LEARNING_STAGE_CHANGED는 서버의 공개 집합을 옮기고 다시 보내지 않는다', async () => {
    stubCsrfPreparation()
    const post = vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(
        409,
        envelopeError('LEARNING_STAGE_CHANGED', '단계의 글이 바뀌었습니다.', {
          articles: [{ articleId: 'tide-reading', draftVersion: 6 }],
        }),
      ),
    )

    await expect(releaseStage('shore-basics', adminReadyReadiness.articles)).resolves.toEqual({
      status: 'changed',
      message: '단계의 글이 바뀌었습니다.',
      articles: [{ articleId: 'tide-reading', draftVersion: 6 }],
    })
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('앞 단계가 공개되지 않았으면 rejected(order)다', async () => {
    stubCsrfPreparation()
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      axiosFailure(409, envelopeError('LEARNING_STAGE_ORDER', '앞 단계를 먼저 공개해야 합니다.')),
    )

    await expect(releaseStage('open-water', [])).resolves.toEqual({
      status: 'rejected',
      reason: 'order',
      message: '앞 단계를 먼저 공개해야 합니다.',
    })
  })
})
