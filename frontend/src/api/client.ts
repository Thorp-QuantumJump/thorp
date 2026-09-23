import axios from 'axios'
import type { ApiErrorCode, ServiceApiErrorCode } from '@/types/api'
import type { AuthFieldError } from '@/types/auth'

/** 공용 API 클라이언트. 개발 중에는 vite proxy(/api → localhost:8080)를 탄다. */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

type AuthenticationRequiredListener = () => void

const authenticationRequiredListeners = new Set<AuthenticationRequiredListener>()

type ApiClientErrorKind = 'api' | 'network' | 'unexpected'

/**
 * 알려진 오류 이름과 비교한다.
 *
 * 첫 인자는 서버가 보낸 열린 값이고 둘째 인자는 **닫힌 union**이라, 비교하는 쪽에서는
 * 자동완성이 되고 오타는 컴파일 단계에서 걸린다. 값을 담을 때만 열어 두고 물을 때는 닫아 두는
 * 것이 이 구조의 핵심이다.
 */
export function isApiErrorCode(
  value: ApiErrorCode | null | undefined,
  code: ServiceApiErrorCode,
): boolean {
  return value === code
}

export class ApiClientError extends Error {
  readonly kind: ApiClientErrorKind
  /** 서버가 보낸 문자열 그대로다. 아는 이름이 아니어도 접거나 버리지 않는다. */
  readonly code: ApiErrorCode | null
  readonly fieldErrors: AuthFieldError[]

  constructor(
    kind: ApiClientErrorKind,
    message: string,
    options?: {
      code?: ApiErrorCode
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

  /** 화면이 자주 묻는 질문 하나를 이름으로 둔다. 오타는 여기서 걸린다. */
  is(code: ServiceApiErrorCode): boolean {
    return isApiErrorCode(this.code, code)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
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

  // **아는 이름인지 묻지 않는다.** 모르는 이름이라고 본문 전체를 버리면 서버가 보낸 안내 문구가
  // 함께 사라지고, 화면은 그 자리에 일반 문구를 대신 내보낸다.
  if (typeof error.code !== 'string' || typeof error.message !== 'string') return null

  const fieldErrors = Array.isArray(error.fieldErrors)
    ? error.fieldErrors.filter(isAuthFieldError)
    : []

  return { code: error.code, message: error.message, fieldErrors }
}

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      const apiError = readApiError(error.response.data)
      if (isApiErrorCode(apiError?.code, 'AUTHENTICATION_REQUIRED')) {
        authenticationRequiredListeners.forEach((listener) => listener())
      }
    }

    return Promise.reject(error)
  },
)

/** 인증된 요청에서 세션 만료가 확인되면 전역 인증 상태에 전달한다. */
export function subscribeAuthenticationRequired(
  listener: AuthenticationRequiredListener,
): () => void {
  authenticationRequiredListeners.add(listener)
  return () => authenticationRequiredListeners.delete(listener)
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

/** CSRF 오류에서는 토큰만 갱신하고 상태 변경 요청은 사용자가 재시도할 때까지 반복하지 않는다. */
export async function runCsrfProtectedMutation<T>(request: () => Promise<T>): Promise<T> {
  try {
    await prepareCsrfToken()
    return await request()
  } catch (error) {
    const normalizedError = normalizeApiError(error)

    if (normalizedError.is('CSRF_TOKEN_INVALID')) {
      resetCsrfToken()
      try {
        await prepareCsrfToken()
      } catch {
        // 다음 명시적 재시도에서도 새 토큰을 준비하도록 실패 상태를 유지한다.
        resetCsrfToken()
      }
    }

    throw normalizedError
  }
}
