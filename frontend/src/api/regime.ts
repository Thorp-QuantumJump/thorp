import { apiClient } from './client'
import type {
  RegimeBaselineMonths,
  RegimeCandidates,
  RegimeCaseDetail,
  RegimeContextQuery,
  RegimeSearchQuery,
  RegimeSearchResult,
} from '@/types/regime'

interface Envelope<T> {
  httpStatus: string
  isSuccess: boolean
  message: string
  code: number
  result: T
}

function resultOf<T>(body: Envelope<T>): T {
  if (
    body?.httpStatus !== 'OK' ||
    body.isSuccess !== true ||
    typeof body.message !== 'string' ||
    body.code !== 200 ||
    body.result == null
  ) {
    throw new Error('경제환경 검색 응답을 읽을 수 없습니다.')
  }
  return body.result
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 신뢰 범위 문구가 빠진 200 응답을 빈 정상 화면으로 렌더링하지 않는다. */
function requireSearchResult(result: RegimeSearchResult): RegimeSearchResult {
  if (
    !isObject(result) ||
    typeof result.statusNote !== 'string' ||
    result.statusNote.length === 0 ||
    !isObject(result.basis) ||
    typeof result.basis.note !== 'string' ||
    result.basis.note.length === 0 ||
    !isObject(result.versions) ||
    typeof result.versions.sampleData !== 'boolean' ||
    !isObject(result.dataAsOf) ||
    !Array.isArray(result.dataAsOf.indicators) ||
    !Array.isArray(result.comparisonMonths) ||
    !result.comparisonMonths.every((item: unknown) =>
      isObject(item) && Array.isArray(item.omitted) &&
      (item.omitted.length === 0 || (typeof item.note === 'string' && item.note.length > 0))) ||
    !isObject(result.counts) ||
    !Array.isArray(result.cases) ||
    (result.counts.eligible > result.counts.stored && !result.displayLimitNote)
  ) {
    throw new Error('경제환경 검색 결과가 계약과 다릅니다.')
  }
  return result
}

function requireBaselines(result: RegimeBaselineMonths): RegimeBaselineMonths {
  if (
    !isObject(result) ||
    !Array.isArray(result.byField) ||
    !result.byField.every((field: unknown) =>
      isObject(field) && typeof field.id === 'string' && typeof field.name === 'string')
  ) {
    throw new Error('경제 분야 목록이 계약과 다릅니다.')
  }
  return result
}

/** 오류는 검색 훅까지 올린다. 화면 문구는 API 응답의 note 필드가 담당한다. */
export async function getRegimeSearch(
  query: RegimeSearchQuery,
  signal?: AbortSignal,
): Promise<RegimeSearchResult> {
  const response = await apiClient.get<Envelope<RegimeSearchResult>>('/regime-searches', {
    params: query,
    signal,
  })
  return requireSearchResult(resultOf(response.data))
}

export async function getRegimeSearchById(
  searchId: string,
  limit: 3 | 10 = 3,
  signal?: AbortSignal,
): Promise<RegimeSearchResult> {
  const response = await apiClient.get<Envelope<RegimeSearchResult>>(
    `/regime-searches/${encodeURIComponent(searchId)}`,
    { params: { limit }, signal },
  )
  return requireSearchResult(resultOf(response.data))
}

export async function getRegimeCaseDetail(
  endMonth: string,
  context: RegimeContextQuery,
  signal?: AbortSignal,
): Promise<RegimeCaseDetail> {
  const response = await apiClient.get<Envelope<RegimeCaseDetail>>(
    `/regime-searches/cases/${encodeURIComponent(endMonth)}`,
    { params: context, signal },
  )
  return resultOf(response.data)
}

export async function getRegimeCandidates(
  context: RegimeContextQuery,
  signal?: AbortSignal,
): Promise<RegimeCandidates> {
  const response = await apiClient.get<Envelope<RegimeCandidates>>('/regime-searches/candidates', {
    params: context,
    signal,
  })
  return resultOf(response.data)
}

export async function getRegimeBaselineMonths(signal?: AbortSignal): Promise<RegimeBaselineMonths> {
  const response = await apiClient.get<Envelope<RegimeBaselineMonths>>(
    '/regime-searches/baseline-months',
    { signal },
  )
  return requireBaselines(resultOf(response.data))
}
