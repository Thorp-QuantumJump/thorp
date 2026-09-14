import axios from 'axios'
import { apiClient } from './client'
import type {
  CandidatePage,
  CandidateSort,
  CandidateStatus,
  ParamSearchDetail,
  ParamSearchEnvelope,
  ParamSearchPreview,
  ParamSearchRequest,
} from '@/types/paramSearch'

/**
 * spec 007 설정 조합 자동 비교 API.
 *
 * 계약은 `docs/specs/param-search-api/README.md`다. 이 모듈은 URL과 응답 껍데기만 알고
 * **전략 지식은 갖지 않는다** — 전략 ID·이름·축·기본값은 전부 호출자가 넘기거나 응답에서 읽는다(FR-025).
 */

const basePath = '/param-searches'

/** 007 오류 코드. 공용 `ApiClientError`는 auth 코드만 알아서 여기서 따로 읽는다. */
export type ParamSearchErrorCode =
  | 'INVALID_INPUT'
  | 'UNREGISTERED_PARAMETER_VALUE'
  | 'CANDIDATE_LIMIT_EXCEEDED'
  | 'NO_VALID_COMBINATION'
  | 'STRATEGY_NOT_REGISTERED'
  | 'STRATEGY_NOT_EXECUTABLE'
  | 'RULES_NOT_REGISTERED'
  | 'REGISTRY_VERSION_CHANGED'
  | 'REGISTRY_UNAVAILABLE'
  | 'ALREADY_FINISHED'
  | 'NOTHING_TO_RETRY'
  | 'RESOURCE_NOT_FOUND'

/**
 * 화면이 분기할 수 있는 007 오류.
 *
 * `code`는 서버가 준 문자열 그대로다 — 위 union에 없는 코드도 그대로 싣는다. 모르는 코드를
 * 아는 코드로 접으면 화면이 잘못된 안내를 하게 된다.
 */
export class ParamSearchError extends Error {
  readonly code: string | null
  readonly status: number | null

  constructor(
    message: string,
    options: { code?: string | null; status?: number | null; cause?: unknown } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'ParamSearchError'
    this.code = options.code ?? null
    this.status = options.status ?? null
  }

  /** 화면이 자주 묻는 질문 하나를 이름으로 둔다. */
  is(code: ParamSearchErrorCode): boolean {
    return this.code === code
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 응답 본문에서 `{ code, message }`를 읽는다. 모양이 다르면 `null`이다. */
function readErrorBody(body: unknown): { code: string; message: string } | null {
  if (!isRecord(body) || !isRecord(body.error)) return null
  const { code, message } = body.error
  if (typeof code !== 'string' || typeof message !== 'string') return null
  return { code, message }
}

/** Axios 오류를 화면이 분기할 수 있는 007 오류로 바꾼다. */
export function normalizeParamSearchError(error: unknown): ParamSearchError {
  if (error instanceof ParamSearchError) return error

  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return new ParamSearchError(
        '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.',
        { cause: error },
      )
    }

    const body = readErrorBody(error.response.data)
    return new ParamSearchError(
      body?.message ?? '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      { code: body?.code ?? null, status: error.response.status, cause: error },
    )
  }

  return new ParamSearchError('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.', {
    cause: error,
  })
}

/**
 * 성공 껍데기를 벗긴다. `success: false`가 200으로 오는 경우도 오류로 다룬다 —
 * 껍데기를 믿고 `data`를 그대로 쓰면 `null`이 화면까지 내려간다.
 */
function unwrap<T>(body: ParamSearchEnvelope<T>): T {
  if (!body.success || body.data === null) {
    throw new ParamSearchError(body.success ? '응답 본문이 비어 있습니다.' : body.error.message, {
      code: body.success ? null : body.error.code,
    })
  }
  return body.data
}

/**
 * 요청자 헤더.
 *
 * **이것은 인증이 아니다.** spec 014 이전까지 백엔드가 `thorp.auth.trust-user-id-header`를 켠
 * 환경에서만 읽는 개발용 통로이고, 접수·취소·재시도가 `backtest_request.requester_id`(NOT NULL)를
 * 채우려면 지금은 이 값이 필요하다. 인증이 붙으면 이 함수와 호출부의 `requesterId`가 함께 사라진다.
 */
function requesterHeaders(requesterId: number | null | undefined) {
  return requesterId == null ? undefined : { 'X-User-Id': String(requesterId) }
}

/**
 * 실행 전 확인. 저장하지 않고 계산만 한다.
 *
 * 확인 화면의 네 항목(대상 전략·탐색 범위 요약·예상 작업량·적용 한도)이 응답 하나에 다 들어 있다.
 * **사용자에게 후보 숫자를 입력받지 않는다**(SC-001).
 */
export async function previewParamSearch(
  request: ParamSearchRequest,
): Promise<ParamSearchPreview> {
  try {
    const response = await apiClient.post<ParamSearchEnvelope<ParamSearchPreview>>(
      `${basePath}/preview`,
      request,
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeParamSearchError(error)
  }
}

/**
 * 탐색 실행. `registryVersion`은 **미리보기에서 받은 값을 그대로 되돌려 보낸다** — 그 사이 등록표가
 * 바뀌었으면 서버가 `409 REGISTRY_VERSION_CHANGED`로 막는다(FR-028).
 *
 * 응답은 `202`이고 뜻이 있는 값은 `searchId`·`status`와 검증 표시뿐이다. 나머지는 폴링으로 받는다.
 */
export async function createParamSearch(
  request: ParamSearchRequest & { registryVersion: string },
  requesterId?: number | null,
): Promise<ParamSearchDetail> {
  try {
    const response = await apiClient.post<ParamSearchEnvelope<ParamSearchDetail>>(
      basePath,
      request,
      { headers: requesterHeaders(requesterId) },
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeParamSearchError(error)
  }
}

/**
 * 묶음 상태·결과. 폴링 대상이다.
 *
 * **실패해도 200이다** — `status`로 판단한다. `selection`이 `null`이면 아직 선정 전이다.
 */
export async function getParamSearch(searchId: string): Promise<ParamSearchDetail> {
  try {
    const response = await apiClient.get<ParamSearchEnvelope<ParamSearchDetail>>(
      `${basePath}/${encodeURIComponent(searchId)}`,
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeParamSearchError(error)
  }
}

/** 후보 목록 질의. 값을 정하지 않은 항목은 보내지 않는다 — 기본값은 서버가 정한다. */
export type CandidateQuery = {
  page?: number
  size?: number
  status?: CandidateStatus
  selected?: boolean
  sort?: CandidateSort
  direction?: 'asc' | 'desc'
}

/**
 * 후보 목록. 정렬 기준은 **서버가 허용하는 다섯 가지뿐**이고 그 밖의 값은 400이다.
 *
 * <p>방향을 생략하면 지표는 내림차순, 순번은 오름차순이다. <b>`asc`·`desc` 외의 방향도 400</b>이라
 * 화면이 오타를 기본값으로 접지 않는다 — 접으면 목록이 조용히 뒤집힌다.
 *
 * <p>정렬은 FR-017이 허용하는 "한 항목 기준 정렬"이다. 정렬했다고 순위가 생기는 것은 아니다.
 */
export async function getParamSearchCandidates(
  searchId: string,
  query: CandidateQuery = {},
): Promise<CandidatePage> {
  const params = new URLSearchParams()
  if (query.page !== undefined) params.set('page', String(query.page))
  if (query.size !== undefined) params.set('size', String(query.size))
  if (query.status) params.set('status', query.status)
  if (query.selected !== undefined) params.set('selected', String(query.selected))
  if (query.sort) {
    params.set('sort', query.direction ? `${query.sort},${query.direction}` : query.sort)
  }

  const search = params.toString()
  try {
    const response = await apiClient.get<ParamSearchEnvelope<CandidatePage>>(
      `${basePath}/${encodeURIComponent(searchId)}/candidates${search ? `?${search}` : ''}`,
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeParamSearchError(error)
  }
}
