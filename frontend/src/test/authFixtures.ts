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
 * <p>결과 화면(005)은 공개 토큰으로 여는 경로라 인증을 몰랐지만, 006의 '기록으로 내보내기'가 로그인 여부로 갈리면서
 * `useAuth`를 지난다(T045a). 실제 앱은 `main.tsx`의 `AuthProvider` 안에서 도므로 테스트도 같은 자리에 둔다 —
 * Provider 없이 렌더하면 `useAuth`가 던진다.
 */
export const anonymousAuthContext = {
  user: null,
  status: 'anonymous',
  login: async () => {},
  logout: async () => {},
  refresh: async () => {},
} as unknown as import('@/auth/auth-context').AuthContextValue
