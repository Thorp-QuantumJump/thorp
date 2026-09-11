import type { ApiErrorEnvelope, ApiSuccessEnvelope, SignupResponse } from '@/types/auth'

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
