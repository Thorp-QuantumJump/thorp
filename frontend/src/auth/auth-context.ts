import { createContext, useContext } from 'react'
import type { AuthenticatedUser, AuthStateStatus, LoginRequest } from '@/types/auth'

export type AuthSnapshot = {
  status: AuthStateStatus
  user: AuthenticatedUser | null
}

export type AuthContextValue = AuthSnapshot & {
  login: (input: LoginRequest, signal?: AbortSignal) => Promise<AuthenticatedUser>
  recheck: () => Promise<AuthSnapshot>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
