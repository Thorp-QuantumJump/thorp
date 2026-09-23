import axios from 'axios'
import { apiClient, ApiClientError } from './client'
import type { ResultEnvelope } from '@/types/result'
import type {
  RegimeLinkAccepted,
  RegimeLinkAvailabilityQuery,
  RegimeLinkAvailabilityResult,
  RegimeLinkCaseWindow,
  RegimeLinkCreateOutcome,
  RegimeLinkCreateRequest,
  RegimeLinkRetryAccepted,
  RegimeLinkSearchCreateRequest,
  RegimeLinkSearchCreated,
  RegimeLinkView,
} from '@/types/regimeLink'

/**
 * 011 사례 연결 API 통로 (contracts `regime-link-api.md`).
 *
 * <p><b>봉투는 005와 같다</b>(`ApiResponse` = `{success, data, error}`). 010의 `regime.ts`가 쓰는 봉투는
 * 다른 모양이라 여기서 재사용하지 않는다 — 두 봉투를 한 함수로 다루면 어느 쪽이 깨져도 드러나지 않는다.
 *
 * <p><b>CSRF와 로그인 복귀는 `apiClient`가 이미 한다.</b> `withCredentials`와 `XSRF-TOKEN` 설정이 거기
 * 있으므로 이 파일은 경로와 본문만 안다.
 */

const basePath = '/regime-links'
const searchPath = '/regime-link-searches'

const unreachableMessage = '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
const unexpectedMessage = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 화면이 분기할 수 있는 011 오류. `code`는 서버가 보낸 이름 그대로다. */
export class RegimeLinkError extends Error {
  readonly code: string | null
  readonly status: number | null
  readonly retryAfterSeconds: number | null

  constructor(
    message: string,
    options?: { code?: string | null; status?: number | null; retryAfterSeconds?: number | null; cause?: unknown },
  ) {
    super(message, { cause: options?.cause })
    this.name = 'RegimeLinkError'
    this.code = options?.code ?? null
    this.status = options?.status ?? null
    this.retryAfterSeconds = options?.retryAfterSeconds ?? null
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readErrorBody(
  data: unknown,
): { code: string | null; message: string | null; retryAfterSeconds: number | null } | null {
  if (!isObject(data)) return null
  const error = isObject(data.error) ? data.error : null
  if (!error) return null
  return {
    code: typeof error.code === 'string' ? error.code : null,
    message: typeof error.message === 'string' ? error.message : null,
    retryAfterSeconds:
      typeof error.retryAfterSeconds === 'number'
        ? error.retryAfterSeconds
        : typeof data.retryAfterSeconds === 'number'
          ? data.retryAfterSeconds
          : null,
  }
}

/** Axios·공용 오류를 011 오류로 옮긴다. 서버가 보낸 이름과 상태 코드를 잃지 않는다. */
export function normalizeRegimeLinkError(error: unknown): RegimeLinkError {
  if (error instanceof RegimeLinkError) return error

  if (error instanceof ApiClientError && axios.isAxiosError(error.cause)) {
    return normalizeRegimeLinkError(error.cause)
  }

  if (axios.isAxiosError(error)) {
    if (!error.response) return new RegimeLinkError(unreachableMessage, { cause: error })
    const body = readErrorBody(error.response.data)
    return new RegimeLinkError(body?.message ?? unexpectedMessage, {
      code: body?.code ?? null,
      status: error.response.status,
      retryAfterSeconds: body?.retryAfterSeconds ?? null,
      cause: error,
    })
  }

  if (error instanceof ApiClientError) {
    return new RegimeLinkError(error.message, { code: error.code, cause: error })
  }

  return new RegimeLinkError(unexpectedMessage, { cause: error })
}

/** `success: false`가 200으로 와도 오류다 — 빈 `data`가 화면까지 내려가면 빈 정상 화면이 그려진다. */
function unwrap<T>(body: ResultEnvelope<T>): T {
  if (!body || !body.success || body.data === null || body.data === undefined) {
    throw new RegimeLinkError(body?.success ? '응답 본문이 비어 있습니다.' : (body?.error?.message ?? unexpectedMessage), {
      code: body?.success ? null : (body?.error?.code ?? null),
    })
  }
  return body.data
}

async function get<T>(path: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<T> {
  try {
    const response = await apiClient.get<ResultEnvelope<T>>(path, { params, signal })
    return unwrap(response.data)
  } catch (error) {
    throw normalizeRegimeLinkError(error)
  }
}

async function post<T>(path: string, body: unknown): Promise<T> {
  try {
    const response = await apiClient.post<ResultEnvelope<T>>(path, body)
    return unwrap(response.data)
  } catch (error) {
    throw normalizeRegimeLinkError(error)
  }
}

/** §1 — 표시 후보에 가용성 판정을 붙인다. 익명도 부를 수 있다. */
export function fetchAvailability(
  query: RegimeLinkAvailabilityQuery,
  signal?: AbortSignal,
): Promise<RegimeLinkAvailabilityResult> {
  return get<RegimeLinkAvailabilityResult>(`${basePath}/availability`, { ...query }, signal)
}

/**
 * §1a — 실행 토큰 없이 사례의 사후 구간을 읽는다.
 *
 * 003이 분석 기간을 고르기 **전에** 부르므로 비교 묶음을 요구하지 않는다.
 */
export function fetchCaseWindow(caseId: string, signal?: AbortSignal): Promise<RegimeLinkCaseWindow> {
  return get<RegimeLinkCaseWindow>(`${basePath}/case-window`, { caseId }, signal)
}

/** §1b — 목록을 확정하고 변경을 기록한다(로그인 필요). */
export function createRegimeLinkSearch(
  request: RegimeLinkSearchCreateRequest,
): Promise<RegimeLinkSearchCreated> {
  return post<RegimeLinkSearchCreated>(searchPath, request)
}

/**
 * §2 — 사례의 전략 결과를 연다(로그인 필요).
 *
 * 202 접수와 200 확인 필요가 같은 자리로 온다. 부르는 쪽은 `outcome`을 보고 갈라야 한다.
 */
export function createRegimeLinks(request: RegimeLinkCreateRequest): Promise<RegimeLinkCreateOutcome> {
  return post<RegimeLinkCreateOutcome>(basePath, request)
}

/** §3 — 링크 하나의 상태와 결과. `token`은 이 링크를 연 분석의 공개 토큰이다. */
export function fetchRegimeLink(
  linkId: string,
  token: string,
  signal?: AbortSignal,
): Promise<RegimeLinkView> {
  return get<RegimeLinkView>(`${basePath}/${encodeURIComponent(linkId)}`, { token }, signal)
}

/** §4 — 사례 하나의 링크 묶음. 비교 묶음 순서(기준 → 비교 대상)로 온다. */
export function listRegimeLinks(
  query: { base: string; compare?: string; caseId: string },
  signal?: AbortSignal,
): Promise<RegimeLinkView[]> {
  return get<RegimeLinkView[]>(basePath, { ...query }, signal)
}

/** §5 — 실패한 링크를 다시 시도한다(로그인 필요). `retryable=true`일 때만 부른다. */
export function retryRegimeLink(linkId: string): Promise<RegimeLinkRetryAccepted> {
  return post<RegimeLinkRetryAccepted>(`${basePath}/${encodeURIComponent(linkId)}/retry`, null)
}

export type { RegimeLinkAccepted }
