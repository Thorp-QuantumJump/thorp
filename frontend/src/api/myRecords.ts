import { ApiClientError, apiClient, normalizeApiError, runCsrfProtectedMutation } from './client'
import type { ApiEnvelope } from '@/types/api'
import type {
  MyRecordDetail,
  MyRecordListItem,
  MyRecordListPage,
  MyRecordListQuery,
  MyRecordRegime,
} from '@/types/myRecords'

/**
 * spec 006 내 기록 API. 계약은 `specs/006-my-records/contracts/my-records-api.md`다.
 *
 * <p>부르는 화면이 있는 것만 둔다 — 목록·상세(006 화면), 경제환경 기록(010 화면), 그리고 목록 줄의 '더 보기'가 쓰는 이름
 * 변경·삭제·내보내기. 부르는 곳 없는 함수는 계약이 바뀌면 고쳐야 할 죽은 코드다.
 *
 * <p>모든 경로가 로그인 필요다. 401은 `apiClient`의 인터셉터가 전역 인증 상태에 알리고, 화면은
 * `ProtectedRoute`가 로그인 안내로 보낸다(S12). 오류 분류는 014의 `normalizeApiError`를 그대로 쓴다 —
 * 006이 새 분류를 만들지 않는다. 남의 기록은 403이 아니라 404다(FR-020).
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

/**
 * 경제환경 검색 결과를 개인 기록으로 남긴다(010 FR-032, research R15). 로그인 사용자가 검색 결과 화면을 열 때
 * 010 화면이 부른다. `entryPath`·`baselineOrigin`은 화면만 아는 값이라 화면이 보내고 서버는 추정하지 않는다.
 * 같은 검색·같은 진입 경로면 기존 항목이 돌아온다(200, `viewed_at`만 갱신).
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

/**
 * 이름 변경(FR-017). <b>이름만</b> 바뀌고 저장된 결과·참조 버전은 그대로다.
 *
 * <p>`name` 외의 키를 보내면 서버가 400으로 거절한다 — 무시하지 않는다. 그래서 이 함수도 다른 값을 함께 보내지 않는다.
 */
export function renameMyRecord(recordId: string, name: string): Promise<MyRecordDetail> {
  return runCsrfProtectedMutation(async () => {
    const response = await apiClient.patch<ApiEnvelope<MyRecordDetail>>(
      `${basePath}/${encodeURIComponent(recordId)}`,
      { name },
    )
    return unwrap(response.data)
  })
}

/**
 * 삭제(FR-017). <b>개인 기록만 사라지고 공용 계산 결과는 남는다.</b>
 *
 * <p>확인은 화면이 받는다 — 서버는 이 요청을 받으면 바로 수행하고 되돌리는 경로가 없다. 이미 지운 항목은 404다.
 */
export function deleteMyRecord(recordId: string): Promise<{ recordId: string; deletedAt: string }> {
  return runCsrfProtectedMutation(async () => {
    const response = await apiClient.delete<ApiEnvelope<{ recordId: string; deletedAt: string }>>(
      `${basePath}/${encodeURIComponent(recordId)}`,
    )
    return unwrap(response.data)
  })
}

/**
 * 내보내기 주소(FR-015·FR-016).
 *
 * <p><b>가져오지 않고 주소만 만든다.</b> 서버가 `Content-Disposition: attachment`로 내려보내므로 브라우저가 직접 받는
 * 것이 맞다 — 여기서 받아 Blob 으로 다시 만들면 파일 이름을 화면이 짓게 되고, 그 이름은 서버가 정한 것과 갈린다.
 */
export function exportMyRecordUrl(recordId: string, format: 'csv' | 'json'): string {
  const base = apiClient.defaults.baseURL ?? ''
  return `${base}${basePath}/${encodeURIComponent(recordId)}/export?format=${format}`
}
