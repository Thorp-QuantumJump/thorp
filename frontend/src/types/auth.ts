import type { SectorApiErrorCode } from './sector'

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

/**
 * 지금 **알고 있는** 서버 오류 신호 이름이다. 도메인이 늘면 그 도메인의 목록을 여기에 이어 붙인다.
 *
 * 이 union은 화면이 "이 오류인가?"를 물을 때 쓴다. 닫혀 있어서 자동완성이 되고 오타가 걸린다.
 * **인증이 쓰던 `authApiErrorCodes`·`AuthApiErrorCode`는 그대로 둔다.**
 */
export type ServiceApiErrorCode = AuthApiErrorCode | SectorApiErrorCode

/**
 * 서버가 실제로 보내는 오류 이름이다. **열려 있다.**
 *
 * 목록을 닫아 두면 모르는 이름이 왔을 때 봉투를 읽는 쪽이 그 응답을 통째로 '예상 못한 것'으로
 * 떨어뜨리고 **서버가 보낸 안내 문구가 일반 문구로 바뀐다.** 다음 도메인이 자기 이름을 목록에
 * 더하는 것을 잊어도 빌드도 검사도 아무것도 실패하지 않으므로, 그 실수는 사용자가 잘못된 안내를
 * 받고 나서야 드러난다. `src/api/paramSearch.ts`가 같은 까닭으로 오류 이름을 열린 문자열로 두고
 * "모르는 코드를 아는 코드로 접으면 화면이 잘못된 안내를 하게 된다"고 적어 두었다.
 *
 * `string & Record<never, never>`는 문자열을 모두 받으면서도 위 union의 자동완성을 살려 두는
 * 표기다. 그냥 `string`으로 두면 알려진 이름의 자동완성이 사라진다.
 */
export type ApiErrorCode = ServiceApiErrorCode | (string & Record<never, never>)

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

/**
 * 봉투가 실어 오는 오류 본문이다. 인증 화면이 쓰던 `AuthApiError` 이름을 그대로 두되,
 * 오류 이름을 서버가 실제로 보낼 수 있는 열린 값으로 넓혔다.
 */
export type AuthApiError = {
  code: ApiErrorCode
  message: string
  fieldErrors?: AuthFieldError[]
}

export type ApiSuccessEnvelope<T> = {
  success: true
  data: T
  error: null
}

export type ApiErrorEnvelope = {
  success: false
  data: null
  error: AuthApiError
}

export type ApiEnvelope<T> = ApiSuccessEnvelope<T> | ApiErrorEnvelope

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
