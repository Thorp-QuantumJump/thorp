import { ApiClientError, apiClient, normalizeApiError, runCsrfProtectedMutation } from './client'
import type { ApiEnvelope } from '@/types/api'
import type {
  MyRecordDetail,
  MyRecordListItem,
  MyRecordListPage,
  MyRecordListQuery,
  MyRecordRegime,
  UsedStrategy,
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
 * 내보내기(FR-015·FR-016·FR-021).
 *
 * <p><b>주소를 앵커로 여는 대신 받아서 내려준다.</b> 앵커는 실패를 알 수 없다 — 그 사이 계산 상태가 바뀌어 409가 오거나
 * 세션이 끊겨 401이 오면 브라우저가 그 오류 본문을 파일로 받아 버리고, 화면은 "내려받습니다"라고 말한 채로 남는다.
 * FR-021이 실패도 알리라고 했으므로 응답을 보고 판단한다.
 *
 * <p>파일 이름은 <b>서버가 정한 것을 그대로 쓴다</b>(`Content-Disposition`). 화면이 지으면 서버가 붙인 이름과 갈린다.
 */
export async function downloadMyRecordExport(
  recordId: string,
  format: 'csv' | 'json',
): Promise<void> {
  const response = await apiClient.get<Blob>(
    `${basePath}/${encodeURIComponent(recordId)}/export`,
    { params: { format }, responseType: 'blob' },
  )
  const name = filenameFrom(
    response.headers['content-disposition'],
    `${recordId}.${format}`,
  )
  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  // 문서에 붙였다 뗀다. 붙이지 않은 앵커의 click 을 무시하는 브라우저가 있다.
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 해제를 다음 턴으로 미룬다. 클릭 직후 동기로 풀면 blob 을 읽기 전에 주소가 죽어 내려받기가
  // 조용히 취소된다 — jsdom 에는 실제 다운로드가 없어 검사로는 드러나지 않는 자리다.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/**
 * `Content-Disposition`의 파일 이름. `filename*`(RFC 5987)이 먼저다 — 한글 이름이 그 자리에 온다.
 *
 * <p>헤더가 없거나 읽지 못하면 부른 쪽이 준 이름을 쓴다. 이름 하나 때문에 내려받기를 실패로 만들지 않는다.
 */
function filenameFrom(disposition: unknown, fallback: string): string {
  if (typeof disposition !== 'string') return fallback
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      return fallback
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1] : fallback
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readUsedStrategy(value: unknown): UsedStrategy | null {
  if (!isRecord(value)) return null
  const { strategyId, recordName, rulesVersion, parameters } = value
  if (typeof strategyId !== 'string' || typeof rulesVersion !== 'string' || !isRecord(parameters))
    return null
  return {
    strategyId,
    recordName: typeof recordName === 'string' && recordName.trim() !== '' ? recordName : null,
    rulesVersion,
    parameters,
  }
}

/**
 * 내가 써 본 전략·설정값 조합(006 contracts `GET /my-records/used-strategies`). 005 비교 후보의 '내 전략' 묶음이다.
 *
 * <p>로그인이 필요한 경로라 비로그인 화면은 부르지 않는다 — 401 을 받아 넘기는 대신 처음부터 기본 전략만 후보로 둔다.
 * 계약에 맞지 않는 항목은 버린다. 조합 하나를 잃는 쪽이 잘못된 요청을 보내는 쪽보다 낫다.
 */
export async function fetchUsedStrategies(signal?: AbortSignal): Promise<UsedStrategy[]> {
  const data = await getResource<{ items?: unknown }>(basePath + '/used-strategies', signal)
  const items = Array.isArray(data.items) ? data.items : []
  return items.map(readUsedStrategy).filter((item): item is UsedStrategy => item !== null)
}
