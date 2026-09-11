import { ApiClientError, apiClient, normalizeApiError, prepareCsrfToken } from './client'
import type {
  ApiEnvelope,
  EmailVerificationConfirmRequest,
  EmailVerificationRequest,
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

/** 값은 남기지 않고 필드별 타입만 모아 예상과 다른 응답 구조를 추적한다. */
function describeShape(value: unknown): string {
  if (!isRecord(value)) return typeof value
  const fields = Object.keys(value)
    .map((key) => `${key}: ${Array.isArray(value[key]) ? 'array' : typeof value[key]}`)
    .join(', ')
  return `{ ${fields} }`
}

async function runMutation<T>(request: () => Promise<T>): Promise<T> {
  try {
    await prepareCsrfToken()
    return await request()
  } catch (error) {
    throw normalizeApiError(error)
  }
}

export async function requestEmailVerification(
  input: EmailVerificationRequest,
  signal?: AbortSignal,
): Promise<void> {
  const email = normalizeEmail(input.email)
  await runMutation(() =>
    apiClient.post('/accounts/email-verifications', { email }, { signal }),
  )
}

export async function confirmEmailVerification(
  input: EmailVerificationConfirmRequest,
  signal?: AbortSignal,
): Promise<void> {
  const email = normalizeEmail(input.email)
  await runMutation(() =>
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
  const response = await runMutation(() =>
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
