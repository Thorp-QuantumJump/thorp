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
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
