import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import type { AuthenticatedUser, SignupResponse } from '@/types/auth'

export const authenticatedUser: AuthenticatedUser = {
  userId: 7,
  email: 'user@example.com',
  nickname: '쏘프사용자',
  status: 'ACTIVE',
}

export const authenticatedUserSuccess: ApiSuccessEnvelope<AuthenticatedUser> = {
  success: true,
  data: authenticatedUser,
  error: null,
}

export const signupSuccess: ApiSuccessEnvelope<SignupResponse> = {
  success: true,
  data: {
    userId: 42,
    email: 'new.user@example.com',
    nickname: '새사용자',
    status: 'ACTIVE',
  },
  error: null,
}

export function authError(
  code: ApiErrorEnvelope['error']['code'],
  message: string,
): ApiErrorEnvelope {
  return {
    success: false,
    data: null,
    error: { code, message },
  }
}
