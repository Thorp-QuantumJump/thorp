import axios from 'axios'
import { ApiClientError, apiClient, prepareCsrfToken, resetCsrfToken } from './client'
import type { ApiEnvelope } from '@/types/api'
import type {
  BacktestAccepted,
  BacktestCreateRequest,
  BacktestRequestStatus,
} from '@/types/backtestRequest'

/**
 * `POST /backtests` 전송(003 T039). 원본 계약은 `docs/specs/backtest-api/README.md` §1이다.
 *
 * 요청은 `utils/analysisRequest.ts`가 조립한 것을 **그대로** 보낸다. 이 파일은 필드를 더하거나 고치지
 * 않는다 — 조립과 전송이 각자 값을 만지면 화면이 보인 조건과 보낸 조건이 갈라진다.
 */

type BacktestRequestErrorKind = 'api' | 'network' | 'unexpected'

/**
 * 분석 요청이 거절되었거나 보내지 못했다.
 *
 * <p><b>`code`와 `details`를 함께 싣는다.</b> 화면은 `code`로 거절마다 계약 §2의 처리를 가르고
 * (`readBacktestRejection`), 400 `UNREGISTERED_PARAMETER_VALUE`면 `details.axis`로 항목 이름을 찾는다
 * (T099). 공용 `ApiClientError`는 `details`를 싣지 않고, Axios 오류의 `code`는 `ERR_BAD_REQUEST` 같은
 * 전송 계층 이름이라 둘 다 그대로 쓸 수 없다.
 */
export class BacktestRequestError extends Error {
  readonly kind: BacktestRequestErrorKind
  /** 서버가 보낸 오류 이름 그대로. 아는 이름이 아니어도 접지 않는다 */
  readonly code: string | null
  readonly status: number | null
  /** 거절 응답의 `details`. 서버가 싣지 않았으면 `null`이다 */
  readonly details: Record<string, unknown> | null

  constructor(
    kind: BacktestRequestErrorKind,
    message: string,
    options: {
      code?: string | null
      status?: number | null
      details?: Record<string, unknown> | null
      cause?: unknown
    } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'BacktestRequestError'
    this.kind = kind
    this.code = options.code ?? null
    this.status = options.status ?? null
    this.details = options.details ?? null
  }
}

const NETWORK_MESSAGE = '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
const UNEXPECTED_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
const UNREADABLE_ACCEPTED_MESSAGE = '분석 요청의 접수 결과를 확인하지 못했습니다.'

const requestStatuses: readonly BacktestRequestStatus[] = [
  'QUEUED',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]
const resultSources: readonly NonNullable<BacktestAccepted['resultSource']>[] = [
  'NEW',
  'JOINED',
  'REUSED',
]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readErrorBody(
  body: unknown,
): { code: string; message: string; details: Record<string, unknown> | null } | null {
  if (!isRecord(body) || !isRecord(body.error)) return null
  const { code, message, details } = body.error
  if (typeof code !== 'string' || typeof message !== 'string') return null
  return { code, message, details: isRecord(details) ? details : null }
}

/** 202 응답의 `data`에서 화면이 쓰는 부분만 읽는다. 모르는 상태 이름이면 읽지 못한 것이다. */
function readAccepted(value: unknown): BacktestAccepted | null {
  if (!isRecord(value)) return null
  const { backtestId, status, resultSource } = value
  if (typeof backtestId !== 'string' || backtestId === '') return null
  if (!requestStatuses.includes(status as BacktestRequestStatus)) return null
  if (resultSource === undefined || resultSource === null) {
    return { backtestId, status: status as BacktestRequestStatus }
  }
  if (!resultSources.includes(resultSource as NonNullable<BacktestAccepted['resultSource']>)) {
    return null
  }
  return {
    backtestId,
    status: status as BacktestRequestStatus,
    resultSource: resultSource as BacktestAccepted['resultSource'],
  }
}

/** 어떤 실패든 `BacktestRequestError` 하나로 옮긴다. 서버가 보낸 문장이 있으면 그것을 쓴다. */
export function normalizeBacktestRequestError(error: unknown): BacktestRequestError {
  if (error instanceof BacktestRequestError) return error

  // CSRF 준비가 실패한 경우. 공용 층이 이미 서버 문장과 이름을 읽어 두었다.
  if (error instanceof ApiClientError) {
    return new BacktestRequestError(error.kind, error.message, { code: error.code, cause: error })
  }

  if (axios.isAxiosError(error)) {
    if (!error.response) return new BacktestRequestError('network', NETWORK_MESSAGE, { cause: error })
    const body = readErrorBody(error.response.data)
    if (!body) {
      return new BacktestRequestError('unexpected', UNEXPECTED_MESSAGE, {
        status: error.response.status,
        cause: error,
      })
    }
    return new BacktestRequestError('api', body.message, {
      code: body.code,
      status: error.response.status,
      details: body.details,
      cause: error,
    })
  }

  return new BacktestRequestError('unexpected', UNEXPECTED_MESSAGE, { cause: error })
}

/**
 * 분석 요청을 접수시킨다. 202면 `backtestId`와 상태를 돌려준다.
 *
 * <p>거절(400·409·422·503 등)과 전송 실패는 모두 `BacktestRequestError`로 거부한다. **다시 보내지
 * 않는다** — 409 뒤 등록표를 다시 받을지, 503 뒤 다시 시도를 제안할지는 화면이 정한다(T059·T099).
 *
 * <p>상태를 바꾸는 요청이라 CSRF 토큰을 먼저 준비한다(계약 §1 인증). 토큰이 무효라는 거절이면 다음
 * 시도에서 새로 받도록 토큰만 비워 두고, 이 요청은 그대로 실패시킨다(`runCsrfProtectedMutation`과 같은
 * 규약). 그 함수를 쓰지 않는 까닭은 오류를 `ApiClientError`로 바꾸며 `details`를 버리기 때문이다.
 */
export async function createBacktest(request: BacktestCreateRequest): Promise<BacktestAccepted> {
  try {
    await prepareCsrfToken()
    const response = await apiClient.post<ApiEnvelope<unknown>>('/backtests', request)
    const body = response.data
    if (!body.success) {
      throw new BacktestRequestError('api', body.error.message, {
        code: body.error.code,
        status: response.status,
      })
    }
    const accepted = readAccepted(body.data)
    if (!accepted) {
      throw new BacktestRequestError('unexpected', UNREADABLE_ACCEPTED_MESSAGE, {
        status: response.status,
      })
    }
    return accepted
  } catch (error) {
    const normalized = normalizeBacktestRequestError(error)
    if (normalized.code === 'CSRF_TOKEN_INVALID') resetCsrfToken()
    throw normalized
  }
}
