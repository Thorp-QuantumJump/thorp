import axios from 'axios'
import { ApiClientError, apiClient, runCsrfProtectedMutation } from './client'
import type {
  PublicResultDetail,
  ResultEnvelope,
  ResultErrorCode,
  ResultExceptions,
  ResultPositions,
  ResultRetryAccepted,
  ResultTimeseries,
  ResultTradePage,
  ResultWeights,
} from '@/types/result'

/**
 * spec 005 공개 결과 열람 API.
 *
 * 계약은 `docs/specs/result-api/README.md`다. 로그인하지 않아도 부를 수 있고, 경로에는 공개 토큰만
 * 쓴다 — 004의 `backtestId`는 이 모듈 어디에도 나오지 않는다(SC-009).
 *
 * <p><b>토큰은 `encodeURIComponent`를 거친다.</b> 정상 토큰은 32자 소문자 16진이라 바뀌지 않는다.
 * 주소창에서 온 값에 `/`나 `..`가 섞여도 다른 경로를 부르지 않고 서버가 `400 INVALID_INPUT`으로 막게 한다.
 */

const basePath = '/results'

/**
 * 화면이 분기할 수 있는 005 오류.
 *
 * `code`는 서버가 준 문자열 그대로다 — `ResultErrorCode`에 없는 코드도 싣는다. 모르는 코드를 아는
 * 코드로 접으면 화면이 잘못된 안내를 한다(`api/paramSearch.ts`와 같은 이유).
 */
export class ResultError extends Error {
  readonly code: string | null
  readonly status: number | null
  /** `429 EXECUTION_LIMIT`의 `details.retryAfterSeconds`. 없으면 `null` */
  readonly retryAfterSeconds: number | null

  constructor(
    message: string,
    options: {
      code?: string | null
      status?: number | null
      retryAfterSeconds?: number | null
      cause?: unknown
    } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'ResultError'
    this.code = options.code ?? null
    this.status = options.status ?? null
    this.retryAfterSeconds = options.retryAfterSeconds ?? null
  }

  /** 화면이 자주 묻는 질문 하나를 이름으로 둔다. 오타는 컴파일 단계에서 걸린다. */
  is(code: ResultErrorCode): boolean {
    return this.code === code
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readErrorBody(
  body: unknown,
): { code: string; message: string; retryAfterSeconds: number | null } | null {
  if (!isRecord(body) || !isRecord(body.error)) return null
  const { code, message, details } = body.error
  if (typeof code !== 'string' || typeof message !== 'string') return null
  const retryAfter = isRecord(details) ? details.retryAfterSeconds : undefined
  return {
    code,
    message,
    retryAfterSeconds: typeof retryAfter === 'number' ? retryAfter : null,
  }
}

const unreachableMessage = '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
const unexpectedMessage = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** Axios 오류를 화면이 분기할 수 있는 005 오류로 바꾼다. */
export function normalizeResultError(error: unknown): ResultError {
  if (error instanceof ResultError) return error

  // CSRF 보호 mutation은 공용 오류로 한 번 감싸서 던진다. 상태 코드와 `details`는 원래 오류에 있다.
  if (error instanceof ApiClientError && axios.isAxiosError(error.cause)) {
    return normalizeResultError(error.cause)
  }

  if (axios.isAxiosError(error)) {
    if (!error.response) return new ResultError(unreachableMessage, { cause: error })

    const body = readErrorBody(error.response.data)
    return new ResultError(body?.message ?? unexpectedMessage, {
      code: body?.code ?? null,
      status: error.response.status,
      retryAfterSeconds: body?.retryAfterSeconds ?? null,
      cause: error,
    })
  }

  if (error instanceof ApiClientError) {
    return new ResultError(error.message, { code: error.code, cause: error })
  }

  return new ResultError(unexpectedMessage, { cause: error })
}

/**
 * 성공 껍데기를 벗긴다. `success: false`가 200으로 와도 오류로 다룬다 — `data`의 `null`이 화면까지
 * 내려가면 빈 결과 화면이 그려진다(SC-008).
 */
function unwrap<T>(body: ResultEnvelope<T>): T {
  if (!body.success || body.data === null) {
    throw new ResultError(body.success ? '응답 본문이 비어 있습니다.' : body.error.message, {
      code: body.success ? null : body.error.code,
    })
  }
  return body.data
}

function resultPath(token: string, suffix = ''): string {
  return `${basePath}/${encodeURIComponent(token)}${suffix}`
}

async function getResource<T>(path: string, signal?: AbortSignal): Promise<T> {
  try {
    const response = await apiClient.get<ResultEnvelope<T>>(path, { signal })
    return unwrap(response.data)
  } catch (error) {
    throw normalizeResultError(error)
  }
}

/**
 * 상태·조건·요약. 폴링 대상이다.
 *
 * <p>분석이 실패해도 200이다 — `status`·`failure`로 판단한다. 만료는 `410 RESULT_EXPIRED`,
 * 없는 토큰은 `404 RESOURCE_NOT_FOUND`, 형식 오류는 `400 INVALID_INPUT`이다.
 */
export function fetchResult(token: string, signal?: AbortSignal): Promise<PublicResultDetail> {
  return getResource(resultPath(token), signal)
}

/** 그래프용 시계열. 받은 그대로 그리고 점 수를 줄이지 않는다(R-08). */
export function fetchResultTimeseries(
  token: string,
  signal?: AbortSignal,
): Promise<ResultTimeseries> {
  return getResource(resultPath(token, '/timeseries'), signal)
}

/** 거래 내역 질의. 정하지 않은 항목은 보내지 않는다 — 기본값과 상한은 서버가 정한다. */
export type ResultTradeQuery = {
  page?: number
  size?: number
  instrumentId?: string
  status?: 'OPEN' | 'CLOSED'
  from?: string
  to?: string
}

/**
 * 거래 내역. `size`는 서버의 공개 상한으로 잘릴 수 있으니 실제 크기는 응답 `size`로 확인한다.
 * 첫 화면에서 부르지 않는다(SC-001).
 */
export function fetchResultTrades(
  token: string,
  query: ResultTradeQuery = {},
  signal?: AbortSignal,
): Promise<ResultTradePage> {
  const params = new URLSearchParams()
  if (query.page !== undefined) params.set('page', String(query.page))
  if (query.size !== undefined) params.set('size', String(query.size))
  if (query.instrumentId) params.set('instrumentId', query.instrumentId)
  if (query.status) params.set('status', query.status)
  if (query.from) params.set('from', query.from)
  if (query.to) params.set('to', query.to)

  const search = params.toString()
  return getResource(resultPath(token, `/trades${search ? `?${search}` : ''}`), signal)
}

/** 그날 이하에서 가장 최근 스냅샷의 보유 구성. 응답의 `snapshotDate`를 함께 보인다. */
export function fetchResultPositions(
  token: string,
  date: string,
  signal?: AbortSignal,
): Promise<ResultPositions> {
  const params = new URLSearchParams({ date })
  return getResource(resultPath(token, `/positions?${params.toString()}`), signal)
}

/** 리밸런싱마다의 목표·실제 비중과 비중 출처. */
export function fetchResultWeights(token: string, signal?: AbortSignal): Promise<ResultWeights> {
  return getResource(resultPath(token, '/weights'), signal)
}

/** 예외 처리 기록. */
export function fetchResultExceptions(
  token: string,
  signal?: AbortSignal,
): Promise<ResultExceptions> {
  return getResource(resultPath(token, '/exceptions'), signal)
}

/**
 * 실패·취소한 결과를 같은 조건으로 다시 접수한다. 응답의 `publicToken`이 새 결과의 주소다.
 *
 * <p>한도에 걸리면(`429 EXECUTION_LIMIT`) `retryAfterSeconds`를 실어 던진다. 조용히 다시 보내지 않는다(원칙 IX).
 */
export async function retryResult(token: string): Promise<ResultRetryAccepted> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ResultEnvelope<ResultRetryAccepted>>(resultPath(token, '/retry'), null),
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeResultError(error)
  }
}
