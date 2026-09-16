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
