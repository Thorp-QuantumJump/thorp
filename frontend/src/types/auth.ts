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

export type AuthApiError = {
  code: AuthApiErrorCode
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
