import { ApiClientError, apiClient, normalizeApiError } from './client'
import type { ApiEnvelope } from '@/types/api'
import type { MyRecordDetail, MyRecordListPage, MyRecordListQuery } from '@/types/myRecords'

/**
 * spec 006 내 기록 API. 계약은 `specs/006-my-records/contracts/my-records-api.md`다.
 *
 * <p>US1이 쓰는 목록·상세만 있다. 이름 변경·삭제·내보내기·경제환경 기록은 그 화면(FE-3·FE-4)이 올 때 계약을 보고
 * 더한다 — 부르는 곳 없는 함수는 계약이 바뀌면 고쳐야 할 죽은 코드다.
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
