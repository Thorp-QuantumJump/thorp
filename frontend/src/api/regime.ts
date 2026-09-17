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

/** 오류는 검색 훅까지 올린다. 화면 문구는 API 응답의 note 필드가 담당한다. */
export async function getRegimeSearch(
  query: RegimeSearchQuery,
  signal?: AbortSignal,
): Promise<RegimeSearchResult> {
  const response = await apiClient.get<Envelope<RegimeSearchResult>>('/regime-searches', {
    params: query,
    signal,
  })
  return resultOf(response.data)
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
  return resultOf(response.data)
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
  return resultOf(response.data)
}
