import {
  ApiClientError,
  apiClient,
  normalizeApiError,
  resetCsrfToken,
  runCsrfProtectedMutation,
} from './client'
import type { ApiEnvelope } from '@/types/api'
import type {
  AuthenticatedUser,
  EmailVerificationConfirmRequest,
  EmailVerificationRequest,
  LoginRequest,
  SignupRequest,
  SignupResponse,
} from '@/types/auth'

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readSignupResponse(value: unknown): SignupResponse | null {
  if (!isRecord(value)) return null
  if (
    typeof value.userId !== 'number' ||
    typeof value.email !== 'string' ||
    typeof value.nickname !== 'string' ||
    value.status !== 'ACTIVE'
  ) {
    return null
  }

  return {
    userId: value.userId,
    email: value.email,
    nickname: value.nickname,
    status: value.status,
  }
}

function readAuthenticatedUser(value: unknown): AuthenticatedUser | null {
  return readSignupResponse(value)
}

/** 값은 남기지 않고 필드별 타입만 모아 예상과 다른 응답 구조를 추적한다. */
function describeShape(value: unknown): string {
  if (!isRecord(value)) return typeof value
  const fields = Object.keys(value)
    .map((key) => `${key}: ${Array.isArray(value[key]) ? 'array' : typeof value[key]}`)
    .join(', ')
  return `{ ${fields} }`
}

export async function requestEmailVerification(
  input: EmailVerificationRequest,
  signal?: AbortSignal,
): Promise<void> {
  const email = normalizeEmail(input.email)
  await runCsrfProtectedMutation(() =>
    apiClient.post('/accounts/email-verifications', { email }, { signal }),
  )
}

export async function confirmEmailVerification(
  input: EmailVerificationConfirmRequest,
  signal?: AbortSignal,
): Promise<void> {
  const email = normalizeEmail(input.email)
  await runCsrfProtectedMutation(() =>
    apiClient.post(
      '/accounts/email-verifications/confirm',
      { email, verificationCode: input.verificationCode },
      { signal },
    ),
  )
}

export async function signup(
  input: SignupRequest,
  signal?: AbortSignal,
): Promise<SignupResponse> {
  const email = normalizeEmail(input.email)
  const response = await runCsrfProtectedMutation(() =>
    apiClient.post<ApiEnvelope<SignupResponse>>(
      '/accounts',
      { ...input, email },
      { signal },
    ),
  )

  const body = response.data
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, {
      code: body.error.code,
      fieldErrors: body.error.fieldErrors,
    })
  }

  const result = readSignupResponse(body.data)
  if (!result) {
    throw new ApiClientError(
      'unexpected',
      '회원가입 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      { cause: new Error(`unexpected signup response shape: ${describeShape(body.data)}`) },
    )
  }

  return result
}

export async function login(
  input: LoginRequest,
  signal?: AbortSignal,
): Promise<AuthenticatedUser> {
  const response = await runCsrfProtectedMutation(() =>
    apiClient.post<ApiEnvelope<AuthenticatedUser>>(
      '/auth/sessions',
      { email: normalizeEmail(input.email), password: input.password },
      { signal },
    ),
  )

  const body = response.data
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, {
      code: body.error.code,
      fieldErrors: body.error.fieldErrors,
    })
  }

  const user = readAuthenticatedUser(body.data)
  if (!user) {
    throw new ApiClientError(
      'unexpected',
      '로그인 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      { cause: new Error(`unexpected login response shape: ${describeShape(body.data)}`) },
    )
  }

  resetCsrfToken()
  return user
}

export async function logout(signal?: AbortSignal): Promise<void> {
  await runCsrfProtectedMutation(() =>
    apiClient.delete('/auth/sessions/current', { signal }),
  )
  resetCsrfToken()
}

export async function getCurrentUser(signal?: AbortSignal): Promise<AuthenticatedUser> {
  try {
    const response = await apiClient.get<ApiEnvelope<AuthenticatedUser>>('/auth/me', { signal })
    const body = response.data

    if (!body.success) {
      throw new ApiClientError('api', body.error.message, {
        code: body.error.code,
        fieldErrors: body.error.fieldErrors,
      })
    }

    const user = readAuthenticatedUser(body.data)
    if (!user) {
      throw new ApiClientError(
        'unexpected',
        '로그인 상태 응답을 확인하지 못했습니다.',
        { cause: new Error(`unexpected current user response shape: ${describeShape(body.data)}`) },
      )
    }

    return user
  } catch (error) {
    throw normalizeApiError(error)
  }
}
