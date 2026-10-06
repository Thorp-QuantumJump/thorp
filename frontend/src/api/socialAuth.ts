import { peekReturnTarget } from '@/auth/returnTarget'
import {
  ApiClientError,
  apiClient,
  normalizeApiError,
  resetCsrfToken,
  runCsrfProtectedMutation,
} from './client'
import type { ApiEnvelope } from '@/types/api'
import {
  socialProviderIds,
  type AuthenticatedUser,
  type PendingSocialView,
  type SocialLinkMethod,
  type SocialLinkRequest,
  type SocialProviderId,
  type SocialSignupRequest,
} from '@/types/auth'

/**
 * 소셜 로그인 API(spec 014 US6·US7, contracts/auth-api.yaml의 소셜 operation).
 *
 * <p>제공자 인가 시작은 Axios 호출이 아니라 **브라우저 전체 이동**이다. 제공자 화면을 다녀오는
 * 동안 탭이 그대로라 `sessionStorage`의 복귀 위치가 보존된다(FR-026). 인가 코드와 제공자 token은
 * 이 화면에 오지 않는다 — 서버가 콜백을 처리하고 판정만 담은 주소로 돌려보낸다.
 */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isProviderId(value: unknown): value is SocialProviderId {
  return typeof value === 'string' && (socialProviderIds as readonly string[]).includes(value)
}

const linkMethods: readonly SocialLinkMethod[] = [
  'PASSWORD',
  'EMAIL_CODE',
  'VERIFIED_EMAIL',
  'SOCIAL_KAKAO',
  'SOCIAL_GOOGLE',
  'SOCIAL_NAVER',
]

function readPending(value: unknown): PendingSocialView | null {
  if (!isRecord(value)) return null
  const stage = value.stage
  if (
    !isProviderId(value.provider) ||
    (stage !== 'EMAIL_VERIFICATION' && stage !== 'SIGNUP' && stage !== 'LINK') ||
    typeof value.email !== 'string' ||
    typeof value.expiresAt !== 'string' ||
    !Array.isArray(value.linkMethods)
  ) {
    return null
  }
  const linkReason = value.linkReason === 'EMAIL' || value.linkReason === 'PHONE' ? value.linkReason : null
  return {
    provider: value.provider,
    stage,
    email: value.email,
    suggestedNickname: typeof value.suggestedNickname === 'string' ? value.suggestedNickname : null,
    linkReason,
    linkTargetMaskedEmail:
      typeof value.linkTargetMaskedEmail === 'string' ? value.linkTargetMaskedEmail : null,
    // 모르는 방법이 와도 아는 것만 보여 준다. 화면이 그릴 수 없는 버튼을 만들지 않는다.
    linkMethods: value.linkMethods.filter((method): method is SocialLinkMethod =>
      (linkMethods as readonly unknown[]).includes(method),
    ),
    expiresAt: value.expiresAt,
  }
}

function readUser(value: unknown): AuthenticatedUser | null {
  if (!isRecord(value)) return null
  if (
    typeof value.userId !== 'number' ||
    typeof value.email !== 'string' ||
    typeof value.nickname !== 'string' ||
    value.status !== 'ACTIVE'
  ) {
    return null
  }
  return { userId: value.userId, email: value.email, nickname: value.nickname, status: 'ACTIVE' }
}

function unwrap<T>(body: ApiEnvelope<unknown>, read: (value: unknown) => T | null, what: string): T {
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, {
      code: body.error.code,
      fieldErrors: body.error.fieldErrors,
    })
  }
  const value = read(body.data)
  if (value === null) {
    throw new ApiClientError('unexpected', `${what} 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.`)
  }
  return value
}

/** 자격 증명이 설정되어 쓸 수 있는 제공자. 실패하면 버튼을 그리지 않도록 빈 목록이다. */
export async function fetchSocialProviders(signal?: AbortSignal): Promise<SocialProviderId[]> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>('/auth/social/providers', { signal })
    const body = response.data
    if (!body.success || !isRecord(body.data) || !Array.isArray(body.data.providers)) return []
    return body.data.providers.filter(isProviderId)
  } catch {
    return []
  }
}

type StartOptions = {
  /** 이메일 제공 동의를 다시 묻는다(FR-022 거절 화면의 "다시 동의"). */
  reprompt?: boolean
  /** 대기 중인 연결을 이미 연결된 이 소셜 계정으로 확인한다(US7). */
  intent?: 'link'
}

/** 시작 주소. 앱의 API 기준 주소(`/api`)를 따른다. */
export function socialLoginStartUrl(provider: SocialProviderId, options: StartOptions = {}): string {
  const base = (apiClient.defaults.baseURL ?? '/api').replace(/\/$/, '')
  const query = new URLSearchParams()
  if (options.reprompt) query.set('reprompt', 'true')
  if (options.intent) query.set('intent', options.intent)
  // 서버가 콜백 뒤 바로 돌아갈 자리. 서버가 다시 검증하고, 없으면 기본 복귀 위치로 보낸다.
  const returnTo = peekReturnTarget()
  if (returnTo) query.set('returnTo', returnTo)
  const suffix = query.toString()
  return `${base}/auth/oauth2/authorization/${provider}${suffix ? `?${suffix}` : ''}`
}

/** 제공자 로그인 화면으로 떠난다. 기존 계정 로그인이면 서버가 적어 둔 자리로, 그 밖은 판정에 맞는 안내 화면으로 보낸다. */
export function startSocialLogin(provider: SocialProviderId, options: StartOptions = {}): void {
  window.location.assign(socialLoginStartUrl(provider, options))
}

export async function getPendingSocial(signal?: AbortSignal): Promise<PendingSocialView> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>('/auth/social/pending', { signal })
    return unwrap(response.data, readPending, '소셜 로그인 진행')
  } catch (error) {
    throw normalizeApiError(error)
  }
}

export async function cancelPendingSocial(signal?: AbortSignal): Promise<void> {
  await runCsrfProtectedMutation(() => apiClient.delete('/auth/social/pending', { signal }))
}

/** 대기 상태가 정한 주소(제공자 이메일 또는 연결 대상 계정 이메일)로 인증번호를 보낸다. */
export async function sendSocialVerificationCode(signal?: AbortSignal): Promise<void> {
  await runCsrfProtectedMutation(() =>
    apiClient.post('/auth/social/pending/email-verifications', undefined, { signal }),
  )
}

/** 제공자 이메일을 확인하고 바뀐 대기 상태(가입 또는 연결 단계)를 돌려준다. */
export async function confirmSocialEmail(
  verificationCode: string,
  signal?: AbortSignal,
): Promise<PendingSocialView> {
  const response = await runCsrfProtectedMutation(() =>
    apiClient.post<ApiEnvelope<unknown>>(
      '/auth/social/pending/email-verifications/confirm',
      { verificationCode },
      { signal },
    ),
  )
  return unwrap(response.data, readPending, '이메일 확인')
}

export async function socialSignup(
  input: SocialSignupRequest,
  signal?: AbortSignal,
): Promise<AuthenticatedUser> {
  const response = await runCsrfProtectedMutation(() =>
    apiClient.post<ApiEnvelope<unknown>>(
      '/auth/social/signup',
      { nickname: input.nickname.trim() },
      { signal },
    ),
  )
  const user = unwrap(response.data, readUser, '가입')
  // 로그인이 새 세션과 새 CSRF 토큰을 만든다. 다음 상태 변경 요청 전에 다시 준비한다.
  resetCsrfToken()
  return user
}

/** 연결하지 않기. 번호가 겹쳐 온 연결이면 서버가 가입 단계로 되돌리고, 이메일이 겹쳐 온 연결이면 끝낸다. */
export async function declineSocialLink(signal?: AbortSignal): Promise<void> {
  await runCsrfProtectedMutation(() =>
    apiClient.post('/auth/social/link/decline', undefined, { signal }),
  )
}

export async function socialLink(
  input: SocialLinkRequest,
  signal?: AbortSignal,
): Promise<AuthenticatedUser> {
  const response = await runCsrfProtectedMutation(() =>
    apiClient.post<ApiEnvelope<unknown>>('/auth/social/link', input, { signal }),
  )
  const user = unwrap(response.data, readUser, '계정 연결')
  resetCsrfToken()
  return user
}
