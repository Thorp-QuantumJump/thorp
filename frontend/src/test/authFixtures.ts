import type { AuthContextValue } from '@/auth/auth-context'
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

/**
 * 비로그인 상태의 인증 컨텍스트.
 *
 * <p>실제 앱은 `main.tsx`의 `AuthProvider` 안에서 도므로 테스트도 같은 자리에 둔다 —
 * Provider 없이 렌더하면 `useAuth`가 던진다. 계약(`AuthContextValue`)의 모양을 그대로 따르므로 계약이 바뀌면
 * 타입 검사가 이 픽스처에서 실패한다. 다시 확인해도 비로그인이고, 로그인·닉네임 변경은 이 픽스처로 하지 않는다.
 */
export const anonymousAuthContext: AuthContextValue = {
  user: null,
  status: 'anonymous',
  login: async () => {
    throw new Error('비로그인 픽스처로는 로그인하지 않는다.')
  },
  logout: async () => {},
  recheck: async () => ({ status: 'anonymous', user: null }),
  updateNickname: async () => {
    throw new Error('비로그인 픽스처로는 닉네임을 바꾸지 않는다.')
  },
}
