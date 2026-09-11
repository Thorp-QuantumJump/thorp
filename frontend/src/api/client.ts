import axios from 'axios'
import type { AuthApiErrorCode, AuthFieldError } from '@/types/auth'

/** 공용 API 클라이언트. 개발 중에는 vite proxy(/api → localhost:8080)를 탄다. */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

type ApiClientErrorKind = 'api' | 'network' | 'unexpected'

export class ApiClientError extends Error {
  readonly kind: ApiClientErrorKind
  readonly code: AuthApiErrorCode | null
  readonly fieldErrors: AuthFieldError[]

  constructor(
    kind: ApiClientErrorKind,
    message: string,
    options?: {
      code?: AuthApiErrorCode
      fieldErrors?: AuthFieldError[]
      cause?: unknown
    },
  ) {
    super(message, { cause: options?.cause })
    this.name = 'ApiClientError'
    this.kind = kind
    this.code = options?.code ?? null
    this.fieldErrors = options?.fieldErrors ?? []
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isAuthApiErrorCode(value: unknown): value is AuthApiErrorCode {
  return (
    typeof value === 'string' &&
    [
      'INVALID_INPUT',
      'DUPLICATE_EMAIL',
      'DUPLICATE_PHONE',
      'EMAIL_VERIFICATION_REQUIRED',
      'EMAIL_VERIFICATION_NOT_FOUND',
      'EMAIL_VERIFICATION_INVALID',
      'EMAIL_VERIFICATION_EXPIRED',
      'INVALID_CREDENTIALS',
      'AUTHENTICATION_REQUIRED',
      'CSRF_TOKEN_INVALID',
      'INTERNAL_ERROR',
    ].includes(value)
  )
}

function isAuthFieldError(value: unknown): value is AuthFieldError {
  if (!isRecord(value)) return false

  return (
    ['email', 'verificationCode', 'password', 'nickname', 'phoneNumber'].includes(
      String(value.field),
    ) && typeof value.message === 'string'
  )
}

function readApiError(value: unknown) {
  if (!isRecord(value) || value.success !== false || !isRecord(value.error)) return null
  const { error } = value

  if (!isAuthApiErrorCode(error.code) || typeof error.message !== 'string') return null

  const fieldErrors = Array.isArray(error.fieldErrors)
    ? error.fieldErrors.filter(isAuthFieldError)
    : []

  return { code: error.code, message: error.message, fieldErrors }
}

/** Axios 오류를 화면에서 안전하게 분기할 수 있는 공통 오류로 변환한다. */
export function normalizeApiError(error: unknown): ApiClientError {
  if (error instanceof ApiClientError) return error

  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return new ApiClientError(
        'network',
        '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.',
        { cause: error },
      )
    }

    const apiError = readApiError(error.response.data)
    if (apiError) {
      return new ApiClientError('api', apiError.message, {
        code: apiError.code,
        fieldErrors: apiError.fieldErrors,
        cause: error,
      })
    }

    return new ApiClientError(
      'unexpected',
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      { cause: error },
    )
  }

  return new ApiClientError(
    'unexpected',
    '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    { cause: error },
  )
}

let csrfReady = false
let pendingCsrfRequest: Promise<void> | null = null

/** 상태 변경 요청 전에 SPA용 CSRF cookie를 한 번 준비한다. */
export async function prepareCsrfToken(): Promise<void> {
  if (csrfReady) return
  if (pendingCsrfRequest) return pendingCsrfRequest

  pendingCsrfRequest = apiClient
    .get('/auth/csrf')
    .then(() => {
      csrfReady = true
    })
    .catch((error: unknown) => {
      throw normalizeApiError(error)
    })
    .finally(() => {
      pendingCsrfRequest = null
    })

  return pendingCsrfRequest
}

/** 로그인·로그아웃 등으로 CSRF token이 회전한 뒤 다음 요청에서 다시 준비한다. */
export function resetCsrfToken(): void {
  csrfReady = false
  pendingCsrfRequest = null
}
