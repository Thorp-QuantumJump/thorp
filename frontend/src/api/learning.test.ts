import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import type { Curriculum, LearningArticle } from '@/types/learning'
import {
  articleSummaries,
  currentArticle,
  harborArticle,
  preReleaseCurriculum,
  previousTideArticle,
  releasedCurriculum,
  stageTwoReleased,
  tideArticle,
} from '@/test/learningFixtures'
import { apiClient } from './client'
import { fetchCurriculum, fetchLearningArticle } from './learning'

/** 013 T026·T141. 빈 목록·볼 수 없는 글·실패를 이 층에서 가르는지 본다(quickstart 4-2, 6-1). */

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

describe('커리큘럼 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('GET /learning/curriculum을 한 번 부르고 받은 단계·안내·기록을 그대로 loaded로 받는다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse(success<Curriculum>(releasedCurriculum)))

    const result = await fetchCurriculum()

    expect(getSpy).toHaveBeenCalledTimes(1)
    expect(getSpy.mock.calls[0][0]).toBe('/learning/curriculum')
    expect(result).toEqual({ status: 'loaded', curriculum: releasedCurriculum })
  })

  it('계약 예시 두 파일(공개 전·1·2단계 공개)을 그대로 읽는다', async () => {
    // 계약 예시가 바뀌었는데 읽는 쪽이 그대로면 화면 테스트는 옛 모양으로 통과한다(005 T039와 같은 이유).
    for (const name of ['curriculum.json', 'curriculum-pre-release.json']) {
      const body = readExample(name)
      vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(body)))
      await expect(fetchCurriculum()).resolves.toEqual({ status: 'loaded', curriculum: body })
    }
  })

  it('게시된 글이 없는 공개 전 커리큘럼도 실패가 아니라 loaded다', async () => {
    const empty: Curriculum = { ...preReleaseCurriculum, preReleaseArticles: [] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(empty)))

    await expect(fetchCurriculum()).resolves.toEqual({ status: 'loaded', curriculum: empty })
  })

  it('공개한 단계 수와 preReleaseArticles가 어긋나면 failed다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get')
    getSpy.mockResolvedValueOnce(
      axiosResponse(success({ ...releasedCurriculum, preReleaseArticles: articleSummaries })),
    )
    getSpy.mockResolvedValueOnce(
      axiosResponse(success({ ...preReleaseCurriculum, preReleaseArticles: null })),
    )

    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('공개한 단계 수가 음수·소수이거나 단계 상태와 어긋나면 빈 목록으로 접지 않고 failed다', async () => {
    // 음수 공개 수와 null preReleaseArticles가 함께 오면 예전에는 '아직 게시된 글이 없습니다'로 그려졌다(원칙 VI).
    const broken = [
      { ...releasedCurriculum, releasedStageCount: -1 },
      { ...preReleaseCurriculum, releasedStageCount: -1, preReleaseArticles: null },
      { ...releasedCurriculum, releasedStageCount: 1.5 },
      { ...releasedCurriculum, releasedStageCount: 3 },
      { ...releasedCurriculum, allStagesReleased: true },
      { ...preReleaseCurriculum, stages: [] },
    ]
    for (const body of broken) {
      vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(body)))
      await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
    }
  })

  it('글이 0편인 공개 단계(REPREPARING)도 공개한 단계로 세어 맞춰 본다', async () => {
    const stages = releasedCurriculum.stages.map((stage) =>
      stage.number === 2 ? { ...stage, status: 'REPREPARING' as const, articles: [] } : stage,
    )
    const body = { ...releasedCurriculum, stages }
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(body)))

    await expect(fetchCurriculum()).resolves.toEqual({ status: 'loaded', curriculum: body })
  })

  it('모르는 단계 상태나 변경 기록 종류는 공개된 것처럼 그리지 않고 failed다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get')
    const [first, ...rest] = releasedCurriculum.stages
    getSpy.mockResolvedValueOnce(
      axiosResponse(success({ ...releasedCurriculum, stages: [{ ...first, status: 'SOON' }, ...rest] })),
    )
    getSpy.mockResolvedValueOnce(
      axiosResponse(
        success({ ...releasedCurriculum, changeLog: [{ ...stageTwoReleased, kind: 'STAGE_PLANNED' }] }),
      ),
    )

    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('글 항목의 필드가 빠지면 빈 단계로 접지 않고 failed다', async () => {
    const [first, ...rest] = releasedCurriculum.stages
    const broken = { ...first, articles: [{ ...first.articles[0], number: undefined }] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse(success({ ...releasedCurriculum, stages: [broken, ...rest] })),
    )

    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('5xx는 서버 코드와 문장을 그대로 담은 failed다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(500, errorEnvelope('INTERNAL_ERROR', '서버 오류입니다.')),
    )

    await expect(fetchCurriculum()).resolves.toEqual({
      status: 'failed',
      kind: 'api',
      code: 'INTERNAL_ERROR',
      message: '서버 오류입니다.',
    })
  })

  it('연결 실패는 reject되지 않고 network failed다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new AxiosError('Network Error'))

    await expect(fetchCurriculum()).resolves.toMatchObject({
      status: 'failed',
      kind: 'network',
      code: null,
    })
  })

  it('계약과 다른 모양은 빈 커리큘럼으로 접지 않고 failed다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success({ articles: [] })))

    await expect(fetchCurriculum()).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
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

/** 013 T134. 판(`edition`)으로 블록 모양을 가르는지 본다(계약 §3, quickstart 9-1). */
describe('배우기 글 하나 조회 — 판별 블록', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  async function read(body: unknown) {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(success(body)))
    return fetchLearningArticle('any-article')
  }

  it('CURRENT 응답의 새 기준 블록과 조각을 그대로 loaded로 받는다', async () => {
    await expect(read(currentArticle)).resolves.toEqual({
      status: 'loaded',
      article: currentArticle,
    })
  })

  it.each([
    ['PRE_RELEASE', tideArticle],
    ['PREVIOUS', previousTideArticle],
  ])('%s 응답의 9월 판 블록을 loaded로 받는다', async (_, article) => {
    await expect(read(article)).resolves.toEqual({ status: 'loaded', article })
  })

  it('CURRENT 응답에 text만 있는 paragraph가 오면 failed다', async () => {
    const mixed = { ...currentArticle, blocks: [{ type: 'paragraph', text: '9월 판 문단' }] }

    await expect(read(mixed)).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('9월 판 응답에 content만 있는 paragraph가 오면 failed다', async () => {
    const mixed = {
      ...tideArticle,
      blocks: [{ type: 'paragraph', content: [{ text: '새 기준 문단' }] }],
    }

    await expect(read(mixed)).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('example이 null이면 CURRENT 글은 loaded이고 9월 판 글은 failed다', async () => {
    await expect(read({ ...currentArticle, example: null })).resolves.toMatchObject({
      status: 'loaded',
      article: { example: null },
    })
    await expect(read({ ...tideArticle, example: null })).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('CURRENT 글의 예시가 객체면 9월 판처럼 검사한다', async () => {
    await expect(read({ ...currentArticle, example: tideArticle.example })).resolves.toMatchObject({
      status: 'loaded',
      article: { example: tideArticle.example },
    })
    await expect(read({ ...currentArticle, example: { available: 'yes' } })).resolves.toMatchObject({
      status: 'failed',
    })
  })

  it.each([
    ['없음', undefined],
    ['모르는 값', 'DRAFT'],
  ])('edition이 %s이면 failed다', async (_, edition) => {
    const { edition: _drop, ...rest } = tideArticle
    void _drop

    await expect(read({ ...rest, edition })).resolves.toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('모르는 블록 종류는 두 판 모두 글 전체를 실패로 만들지 않는다', async () => {
    const future = { type: 'timeline', items: [] }

    await expect(
      read({ ...currentArticle, blocks: [future, ...currentArticle.blocks] }),
    ).resolves.toMatchObject({ status: 'loaded' })
    await expect(
      read({ ...tideArticle, blocks: [future, ...tideArticle.blocks] }),
    ).resolves.toMatchObject({ status: 'loaded' })
  })

  it('모르는 도식 종류는 글 전체를 실패로 만들지 않고 종류를 null로 받는다', async () => {
    const future = {
      ...currentArticle,
      blocks: [
        {
          type: 'diagram',
          kind: 'area',
          title: '',
          unit: '',
          categories: ['가', '나'],
          series: [{ name: '가', values: [1, 2] }],
          description: '설명 문장',
        },
      ],
    }

    await expect(read(future)).resolves.toMatchObject({
      status: 'loaded',
      article: { blocks: [{ type: 'diagram', kind: null, description: '설명 문장' }] },
    })
  })

  it('모양이 틀린 글자 조각은 문장을 비워 그리지 않고 failed다', async () => {
    const broken = {
      ...currentArticle,
      blocks: [{ type: 'paragraph', content: [{ term: 'ebb-gap', text: '썰물 간격' }] }],
    }

    await expect(read(broken)).resolves.toMatchObject({ status: 'failed', kind: 'unexpected' })
  })
})
