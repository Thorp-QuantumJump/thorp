import { ApiClientError, apiClient, normalizeApiError, runCsrfProtectedMutation } from './client'
import type { ApiEnvelope } from '@/types/api'
import type {
  MyRecordDetail,
  MyRecordExportFormat,
  MyRecordListItem,
  MyRecordListPage,
  MyRecordListQuery,
  MyRecordRegime,
} from '@/types/myRecords'

/**
 * spec 006 내 기록 API. 계약은 `specs/006-my-records/contracts/my-records-api.md`다.
 *
 * <p>모든 경로가 로그인 필요다. 401은 `apiClient`의 인터셉터가 전역 인증 상태에 알리고, 화면은
 * `ProtectedRoute`가 로그인 안내로 보낸다(S12). 오류 분류는 014의 `normalizeApiError`를 그대로 쓴다 —
 * 006이 새 분류를 만들지 않는다.
 *
 * <p>남의 기록은 403이 아니라 404다(FR-020). 화면은 둘을 구분할 필요가 없고 서버도 알려 주지 않는다.
 */

const basePath = '/my-records'

/**
 * 성공 껍데기를 벗긴다. `success: false`가 200으로 와도 오류로 다룬다 — `data`의 `null`이 화면까지
 * 내려가면 빈 목록이 "기록이 없다"는 거짓이 된다(원칙 VI).
 */
function unwrap<T>(body: ApiEnvelope<T>): T {
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, { code: body.error.code })
  }
  if (body.data === null || body.data === undefined) {
    throw new ApiClientError('unexpected', '응답 본문이 비어 있습니다.')
  }
  return body.data
}

/** 배열 항목은 같은 이름을 여러 번 보낸다(계약 "여러 번 줄 수 있다"). 비우면 보내지 않는다. */
export function toSearchParams(query: MyRecordListQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.cursor) params.set('cursor', query.cursor)
  if (query.size !== undefined) params.set('size', String(query.size))
  if (query.sort) params.set('sort', query.sort)
  query.kind?.forEach((value) => params.append('kind', value))
  query.status?.forEach((value) => params.append('status', value))
  query.sector?.forEach((value) => params.append('sector', value))
  query.strategy?.forEach((value) => params.append('strategy', value))
  if (query.periodFrom) params.set('periodFrom', query.periodFrom)
  if (query.periodTo) params.set('periodTo', query.periodTo)
  return params
}

async function getResource<T>(path: string, signal?: AbortSignal): Promise<T> {
  try {
    const response = await apiClient.get<ApiEnvelope<T>>(path, { signal })
    return unwrap(response.data)
  } catch (error) {
    throw normalizeApiError(error)
  }
}

/** 목록 한 쪽. 상태를 비우면 서버의 기본 보기(완료 항목)다. */
export async function fetchMyRecords(
  query: MyRecordListQuery = {},
  signal?: AbortSignal,
): Promise<MyRecordListPage> {
  const params = toSearchParams(query).toString()
  return getResource<MyRecordListPage>(params ? `${basePath}?${params}` : basePath, signal)
}

/** 상세. 남의 것·없는 것·지운 것은 모두 404 `RESOURCE_NOT_FOUND`다. */
export function fetchMyRecord(recordId: string, signal?: AbortSignal): Promise<MyRecordDetail> {
  return getResource<MyRecordDetail>(`${basePath}/${encodeURIComponent(recordId)}`, signal)
}

/** 이름만 바뀐다. 저장된 결과와 참조 버전은 그대로다(FR-017). 응답은 갱신된 상세다. */
export function renameMyRecord(recordId: string, name: string): Promise<MyRecordDetail> {
  return runCsrfProtectedMutation(async () => {
    const response = await apiClient.patch<ApiEnvelope<MyRecordDetail>>(
      `${basePath}/${encodeURIComponent(recordId)}`,
      { name },
    )
    return unwrap(response.data)
  })
}

/** 개인 항목만 지운다. 공용 계산 결과는 남는다(FR-017). 확인은 화면이 받는다. 복구는 없다. */
export function deleteMyRecord(
  recordId: string,
): Promise<{ recordId: string; deletedAt: string }> {
  return runCsrfProtectedMutation(async () => {
    const response = await apiClient.delete<ApiEnvelope<{ recordId: string; deletedAt: string }>>(
      `${basePath}/${encodeURIComponent(recordId)}`,
    )
    return unwrap(response.data)
  })
}

/**
 * 경제환경 검색 결과를 개인 기록으로 남긴다(010 FR-032, research R15). `baselineOrigin`은 010 응답의 값을
 * 화면이 그대로 보낸다 — 서버는 추정하지 않는다. 같은 검색·같은 진입 경로면 기존 항목이 돌아온다(200).
 */
export function recordRegimeSearch(
  searchId: string,
  entryPath: MyRecordRegime['entryPath'],
  baselineOrigin: MyRecordRegime['baselineOrigin'],
): Promise<MyRecordListItem> {
  return runCsrfProtectedMutation(async () => {
    const response = await apiClient.post<ApiEnvelope<MyRecordListItem>>(
      `${basePath}/regime-searches`,
      { searchId, entryPath, baselineOrigin },
    )
    return unwrap(response.data)
  })
}

/** 내보내기는 파일 응답이라 봉투를 쓰지 않는다. 화면이 이 주소를 `<a download>`에 건다. */
export function exportMyRecordUrl(recordId: string, format: MyRecordExportFormat): string {
  const base = apiClient.defaults.baseURL ?? '/api'
  return `${base}${basePath}/${encodeURIComponent(recordId)}/export?format=${format}`
}
