import { createContext, useContext } from 'react'
import type { AuthenticatedUser, AuthStateStatus, LoginRequest } from '@/types/auth'

export type AuthSnapshot = {
  status: AuthStateStatus
  user: AuthenticatedUser | null
}

export type AuthContextValue = AuthSnapshot & {
  login: (input: LoginRequest, signal?: AbortSignal) => Promise<AuthenticatedUser>
  logout: (signal?: AbortSignal) => Promise<void>
  recheck: () => Promise<AuthSnapshot>
  /** 닉네임을 바꾸고 로그인 상태의 사용자를 바뀐 값으로 갈아 끼운다. 재확인처럼 `checking`을 거치지 않는다. */
  updateNickname: (nickname: string, signal?: AbortSignal) => Promise<AuthenticatedUser>
  /**
   * 서버가 이미 세션을 만든 로그인(소셜 가입·연결, spec 014)의 결과로 로그인 상태를 정한다.
   *
   * `recheck`를 쓰지 않는 까닭: 재확인은 `checking`을 거치므로 인증 화면 가드가 "확인 중 → 로그인됨"으로 보고
   * 복귀 위치 대신 기본 화면으로 보낸다. 이메일 로그인(`login`)처럼 곧바로 `authenticated`가 되어야 한다.
   */
  completeSignIn: (user: AuthenticatedUser) => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
