import { ApiClientError, apiClient, normalizeApiError } from './client'
import type { ApiEnvelope, ApiErrorCode } from '@/types/api'
import type {
  ArticleBlock,
  LearningArticle,
  LearningArticleList,
  LearningArticleSummary,
  LearningExample,
  LearningExampleSector,
  LearningSource,
} from '@/types/learning'

/**
 * 배우기 공개 조회(013 contracts/learning-public-api.md §1·§2)의 통로다.
 *
 * <p><b>빈 목록과 실패를 이 층에서 가른다.</b> 게시된 글이 없는 200과 목록을 받지 못한 실패는 사용자가
 * 할 수 있는 일이 다르다(013 SC-007). 화면이 배열 길이로 판정하면 실패를 빈 목록으로 그리기 쉽다 —
 * `api/sectors.ts`의 `SectorCatalogResult`가 같은 이유로 같은 구조를 쓴다.
 *
 * <p><b>예외를 던지지 않고 재시도하지 않는다.</b> 다시 시도는 사용자가 누를 때 다시 부른다(013 FR-015).
 * 오류 분류는 014의 `normalizeApiError`를 그대로 쓴다.
 */

type LoadFailure = {
  status: 'failed'
  kind: 'api' | 'network' | 'unexpected'
  /** 서버가 보낸 이름 그대로다. 아는 이름이 아니어도 버리지 않는다 */
  code: ApiErrorCode | null
  message: string
}

export type LearningListResult =
  | { status: 'loaded'; articles: LearningArticleSummary[] }
  /** 게시된 글이 없다. 오류가 아니라 정상 화면이다 */
  | { status: 'empty' }
  | LoadFailure

export type LearningArticleResult =
  | { status: 'loaded'; article: LearningArticle }
  /** 404 `LEARNING_ARTICLE_UNAVAILABLE`. 없는 글과 게시되지 않은 글을 서버가 가르지 않는다 */
  | { status: 'unavailable' }
  | LoadFailure

const UNREADABLE_RESPONSE_MESSAGE = '배우기 글을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function readSummary(value: unknown): LearningArticleSummary | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isString(value.title) ||
    !isString(value.question) ||
    typeof value.readingOrder !== 'number'
  ) {
    return null
  }
  return {
    articleId: value.articleId,
    title: value.title,
    question: value.question,
    readingOrder: value.readingOrder,
  }
}

function readList(value: unknown): LearningArticleList | null {
  if (!isRecord(value) || !Array.isArray(value.articles)) return null
  const articles: LearningArticleSummary[] = []
  for (const item of value.articles) {
    const summary = readSummary(item)
    if (!summary) return null
    articles.push(summary)
  }
  return { articles }
}

/**
 * 블록 하나를 읽는다.
 *
 * <b>모르는 `type`은 읽지 못한 응답으로 다루지 않는다.</b> 서버가 새 블록 종류를 더해도 글 전체가
 * 실패로 바뀌지 않게 그대로 넘기고, 그리지 않는 판단은 `ArticleBlocks`가 한다. 아는 종류인데 필드가
 * 빠졌으면 계약 위반이므로 빈 글로 채우지 않고 응답 전체를 읽지 못한 것으로 둔다.
 */
function readBlock(value: unknown): ArticleBlock | null {
  if (!isRecord(value) || !isString(value.type)) return null
  switch (value.type) {
    case 'heading':
    case 'paragraph':
    case 'hypothetical':
      return isString(value.text) ? { type: value.type, text: value.text } : null
    case 'term':
      return isString(value.termId) && isString(value.label) && isString(value.sentence)
        ? { type: 'term', termId: value.termId, label: value.label, sentence: value.sentence }
        : null
    default:
      return value as unknown as ArticleBlock
  }
}

function readExampleSector(value: unknown): LearningExampleSector | null {
  if (
    !isRecord(value) ||
    !isString(value.code) ||
    !(value.name === null || isString(value.name)) ||
    typeof value.weightTenths !== 'number'
  ) {
    return null
  }
  return { code: value.code, name: value.name, weightTenths: value.weightTenths }
}

function readExample(value: unknown): LearningExample | null {
  if (
    !isRecord(value) ||
    typeof value.available !== 'boolean' ||
    !Array.isArray(value.sectors) ||
    !Array.isArray(value.missingSectorCodes) ||
    !value.missingSectorCodes.every(isString)
  ) {
    return null
  }
  const reason = value.unavailableReason
  if (reason !== null && reason !== 'SECTOR_NOT_IN_MAP' && reason !== 'SECTOR_MAP_NOT_READY') {
    return null
  }
  const sectors: LearningExampleSector[] = []
  for (const item of value.sectors) {
    const sector = readExampleSector(item)
    if (!sector) return null
    sectors.push(sector)
  }
  return {
    available: value.available,
    sectors,
    missingSectorCodes: value.missingSectorCodes,
    unavailableReason: reason,
  }
}

function readSource(value: unknown): LearningSource | null {
  if (!isRecord(value) || !isString(value.label)) return null
  // 계약이 "url은 없을 수 있다"고 정했다. 키가 없는 것과 null을 같은 뜻으로 받는다.
  if (value.url !== undefined && value.url !== null && !isString(value.url)) return null
  return { label: value.label, url: value.url ?? null }
}

function readArticle(value: unknown): LearningArticle | null {
  const summary = readSummary(value)
  if (!summary || !isRecord(value)) return null
  if (!Array.isArray(value.blocks) || !Array.isArray(value.sources)) return null
  if (!isString(value.publishedAt)) return null

  const blocks: ArticleBlock[] = []
  for (const item of value.blocks) {
    const block = readBlock(item)
    if (!block) return null
    blocks.push(block)
  }
  const sources: LearningSource[] = []
  for (const item of value.sources) {
    const source = readSource(item)
    if (!source) return null
    sources.push(source)
  }
  const example = readExample(value.example)
  if (!example) return null

  return { ...summary, blocks, example, sources, publishedAt: value.publishedAt }
}

function unreadable(): LoadFailure {
  return { status: 'failed', kind: 'unexpected', code: null, message: UNREADABLE_RESPONSE_MESSAGE }
}

function toFailure(error: ApiClientError): LoadFailure {
  return { status: 'failed', kind: error.kind, code: error.code, message: error.message }
}

/** 봉투를 벗긴다. 실패 봉투가 200으로 오면 오류로 바꿔 던진다 — 아래 `catch`가 받는다. */
function unwrap<T>(body: ApiEnvelope<T>): T {
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, { code: body.error.code })
  }
  return body.data
}

/** 게시된 글 목록을 한 번 받아 온다. */
export async function fetchLearningArticles(signal?: AbortSignal): Promise<LearningListResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<LearningArticleList>>('/learning/articles', {
      signal,
    })
    const list = readList(unwrap(response.data))
    if (!list) return unreadable()
    return list.articles.length === 0
      ? { status: 'empty' }
      : { status: 'loaded', articles: list.articles }
  } catch (error) {
    return toFailure(normalizeApiError(error))
  }
}

/** 게시된 글 하나를 받아 온다. */
export async function fetchLearningArticle(
  articleId: string,
  signal?: AbortSignal,
): Promise<LearningArticleResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<LearningArticle>>(
      `/learning/articles/${encodeURIComponent(articleId)}`,
      { signal },
    )
    const article = readArticle(unwrap(response.data))
    return article ? { status: 'loaded', article } : unreadable()
  } catch (error) {
    const normalized = normalizeApiError(error)
    if (normalized.kind === 'api' && normalized.is('LEARNING_ARTICLE_UNAVAILABLE')) {
      return { status: 'unavailable' }
    }
    return toFailure(normalized)
  }
}
