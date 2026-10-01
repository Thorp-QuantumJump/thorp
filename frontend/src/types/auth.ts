/**
 * 인증 도메인의 타입이다.
 *
 * **이 파일은 응답 봉투를 갖지 않는다.** 봉투와 서버 오류 이름의 공용 타입은 모든 도메인이 함께
 * 쓰는 것이라 `./api`에 있다. 그쪽이 이 파일을 가져오고 이 파일은 그쪽을 가져오지 않는다 —
 * 방향을 한쪽으로 고정해 순환 참조를 막는다. 까닭은 `types/api.ts`의 「의존 방향」에 적었다.
 *
 * 여기 남은 것은 인증에만 속하는 이름이다. 다른 도메인이 오류 이름을 더할 때 이 파일을 고칠
 * 일은 없다.
 */
export const authApiErrorCodes = [
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
  // 소셜 로그인(spec 014, 2026-10-01). 비밀번호·인증번호 오류는 위 이름을 그대로 쓴다.
  'SOCIAL_PENDING_NOT_FOUND',
  'SOCIAL_PENDING_STAGE_MISMATCH',
  'SOCIAL_SIGNUP_REJECTED',
  'SOCIAL_LINK_REQUIRED',
  'SOCIAL_PROVIDER_ALREADY_LINKED',
  'SOCIAL_ACCOUNT_ALREADY_LINKED',
  'SOCIAL_PROVIDER_NOT_ENABLED',
  'SOCIAL_VERIFICATION_LIMIT',
  'EMAIL_VERIFICATION_RESEND_TOO_SOON',
  'INTERNAL_ERROR',
] as const

export type AuthApiErrorCode = (typeof authApiErrorCodes)[number]

export type AuthFieldName =
  | 'email'
  | 'verificationCode'
  | 'password'
  | 'nickname'
  | 'phoneNumber'

export type AuthFieldError = {
  field: AuthFieldName
  code?: string
  message: string
}

export type EmailVerificationRequest = {
  email: string
}

export type EmailVerificationConfirmRequest = {
  email: string
  verificationCode: string
}

export type SignupRequest = {
  email: string
  password: string
  nickname: string
  phoneNumber: string
}

export type LoginRequest = {
  email: string
  password: string
}

export type SignupResponse = {
  userId: number
  email: string
  nickname: string
  status: 'ACTIVE'
}

export type AuthenticatedUser = {
  userId: number
  email: string
  nickname: string
  status: 'ACTIVE'
}

export type AuthStateStatus =
  | 'checking'
  | 'authenticated'
  | 'anonymous'
  | 'expired'
  | 'error'
  | 'logoutUnconfirmed'

export type RequestStatus = 'idle' | 'pending' | 'success' | 'error'

export type VerificationState = 'idle' | 'sent' | 'verified' | 'error' | 'expired'

/** 소셜 제공자. 외부 경로와 계약(`SocialProviderId`)의 표기다. */
export const socialProviderIds = ['kakao', 'naver', 'google'] as const

export type SocialProviderId = (typeof socialProviderIds)[number]

export type SocialPendingStage = 'EMAIL_VERIFICATION' | 'SIGNUP' | 'LINK'

/** 기존 계정 소유 확인 방법. `SOCIAL_*`은 그 제공자로 한 번 더 로그인하는 방법이다. */
export type SocialLinkMethod =
  | 'PASSWORD'
  | 'EMAIL_CODE'
  | 'VERIFIED_EMAIL'
  | 'SOCIAL_KAKAO'
  | 'SOCIAL_GOOGLE'
  | 'SOCIAL_NAVER'

/**
 * 소셜 가입·연결 대기 상태 중 화면에 보여도 되는 값(contracts `PendingSocialView`).
 *
 * 서버 세션에만 있는 값을 읽어 온 것이다. 화면은 이것을 메모리에만 들고 저장소에 쓰지 않는다.
 */
export type PendingSocialView = {
  provider: SocialProviderId
  stage: SocialPendingStage
  email: string
  suggestedNickname: string | null
  linkReason: 'EMAIL' | 'PHONE' | null
  linkTargetMaskedEmail: string | null
  linkMethods: SocialLinkMethod[]
  expiresAt: string
}

export type SocialSignupRequest = {
  nickname: string
  phoneNumber: string
}

export type SocialLinkRequest =
  | { method: 'PASSWORD'; password: string }
  | { method: 'EMAIL_CODE'; verificationCode: string }
  | { method: 'VERIFIED_EMAIL' }
