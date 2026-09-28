import { apiClient, normalizeApiError } from './client'
import type {
  NewsArticle,
  NewsDigest,
  NewsDigestResult,
  NewsDigestState,
  NewsSector,
  PublicationTimeStatus,
} from '@/types/marketNews'

/**
 * 012의 뉴스 묶음을 읽는다. 009는 자리를 내주고 값을 그대로 그릴 뿐 내용·수집·표시 규칙을
 * 정하지 않는다(FR-019).
 *
 * **네 상태는 모두 정상 응답이다.** `EMPTY`·`PREPARING`·`STALE`을 예외로 던지면 뉴스가 없다는
 * 이유로 시장 흐름 화면이 오류로 떨어진다.
 */

const states: NewsDigestState[] = ['READY', 'EMPTY', 'STALE', 'PREPARING']
const timeStatuses: PublicationTimeStatus[] = ['VERIFIED', 'UNKNOWN']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isArticle(value: unknown): value is NewsArticle {
  return (
    isRecord(value) &&
    typeof value.articleId === 'string' &&
    typeof value.title === 'string' &&
    typeof value.publisher === 'string' &&
    isNullableString(value.publishedAt) &&
    timeStatuses.includes(value.publicationTimeStatus as PublicationTimeStatus) &&
    typeof value.originalUrl === 'string'
  )
}

function isDigest(value: unknown): value is NewsDigest {
  return (
    isRecord(value) &&
    typeof value.digestId === 'string' &&
    typeof value.representativeTitle === 'string' &&
    typeof value.factualSummary === 'string' &&
    isNullableString(value.firstPublishedAt) &&
    timeStatuses.includes(value.publicationTimeStatus as PublicationTimeStatus) &&
    typeof value.publisherCount === 'number' &&
    isStringArray(value.sectors) &&
    isStringArray(value.topics) &&
    typeof value.importanceRuleVersion === 'string' &&
    Array.isArray(value.articles) &&
    // 계약의 minItems: 1이다. 기사 없는 묶음은 대표 제목만 있고 원문으로 갈 길이 없다.
    value.articles.length > 0 &&
    value.articles.every(isArticle)
  )
}

function parseDigestEnvelope(value: unknown): NewsDigestResult {
  if (
    !isRecord(value) ||
    value.httpStatus !== 'OK' ||
    value.isSuccess !== true ||
    value.code !== 200 ||
    !isRecord(value.result)
  ) {
    throw new Error('시장 흐름 뉴스 응답 형식이 계약과 다릅니다.')
  }

  const result = value.result
  if (
    (result.context !== 'CURRENT' && result.context !== 'HISTORICAL') ||
    !states.includes(result.state as NewsDigestState) ||
    !isNullableString(result.sectorId) ||
    !isNullableString(result.from) ||
    !isNullableString(result.to) ||
    !isNullableString(result.lastUpdatedAt) ||
    typeof result.stale !== 'boolean' ||
    !isNullableString(result.statusMessage) ||
    !Array.isArray(result.digests) ||
    !result.digests.every(isDigest)
  ) {
    throw new Error('시장 흐름 뉴스 묶음을 읽을 수 없습니다.')
  }

  return result as NewsDigestResult
}

/**
 * 과거 구간 조회의 경계(012 US3). UTC ISO-8601이며 012가 최대 93일로 제한한다.
 *
 * 011이 사례의 관측 구간 3개월을 넘길 때 쓴다(FR-024). **011은 값을 계산해 넓히지 않는다** —
 * 넘긴 구간을 벗어난 응답은 012가 거절한다.
 */
export type NewsDigestRange = { from?: string; to?: string }

/**
 * `sectorId`는 **선택**이다. 고르지 않았으면 붙이지 않는다.
 *
 * 012가 형식을 검증하지 않고 섹터 목록(`fetchNewsSectors`)에 있는지만 보므로 화면도 모양을 따지지 않는다.
 * 여기서 규칙을 만들면 012가 규칙을 바꾼 날 화면만 거부하는 값이 생긴다.
 */
export async function fetchMarketNewsDigests(
  sectorId?: string | null,
  signal?: AbortSignal,
  range?: NewsDigestRange,
): Promise<NewsDigestResult> {
  try {
    const params: Record<string, string> = {}
    if (sectorId) params.sectorId = sectorId
    if (range?.from) params.from = range.from
    if (range?.to) params.to = range.to
    const response = await apiClient.get<unknown>('/market-news/digests', {
      params: Object.keys(params).length > 0 ? params : undefined,
      signal,
    })
    return parseDigestEnvelope(response.data)
  } catch (error) {
    throw normalizeApiError(error)
  }
}

function isNewsSector(value: unknown): value is NewsSector {
  return (
    isRecord(value) &&
    typeof value.sectorId === 'string' &&
    typeof value.name === 'string' &&
    typeof value.investable === 'boolean'
  )
}

/**
 * 뉴스를 좁혀 볼 섹터 목록(012 FR-005, Session 2026-09-25). 투자 대상 섹터와 뉴스 전용 섹터가 화면 순서로 온다.
 * 화면은 이 목록으로 칩을 그리고 섹터 이름을 상수로 두지 않는다.
 */
export async function fetchNewsSectors(signal?: AbortSignal): Promise<NewsSector[]> {
  try {
    const response = await apiClient.get<unknown>('/market-news/sectors', { signal })
    const value = response.data
    if (
      !isRecord(value) ||
      value.isSuccess !== true ||
      !isRecord(value.result) ||
      !Array.isArray(value.result.sectors) ||
      !value.result.sectors.every(isNewsSector)
    ) {
      throw new Error('뉴스 섹터 목록을 읽을 수 없습니다.')
    }
    return value.result.sectors as NewsSector[]
  } catch (error) {
    throw normalizeApiError(error)
  }
}
