import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { getCurrentUser, login as requestLogin } from '@/api/auth'
import { ApiClientError } from '@/api/client'
import type { AuthenticatedUser, LoginRequest } from '@/types/auth'
import { AuthContext, type AuthContextValue, type AuthSnapshot } from './auth-context'

let pendingBootstrap: Promise<AuthenticatedUser> | null = null

function loadBootstrapUser(): Promise<AuthenticatedUser> {
  if (!pendingBootstrap) {
    pendingBootstrap = getCurrentUser().finally(() => {
      pendingBootstrap = null
    })
  }
  return pendingBootstrap
}

function rejectedSnapshot(error: unknown): AuthSnapshot {
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
    return { status: 'anonymous', user: null }
  }
  return { status: 'error', user: null }
}

/** 서버 세션을 기준으로 앱 전체의 인증 상태를 한 곳에서 관리한다. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<AuthSnapshot>({
    status: 'checking',
    user: null,
  })
  const mountedRef = useRef(true)
  const requestIdRef = useRef(0)

  const applyCurrentUserRequest = useCallback(
    async (request: Promise<AuthenticatedUser>): Promise<AuthSnapshot> => {
      const requestId = ++requestIdRef.current

      try {
        const user = await request
        const next: AuthSnapshot = { status: 'authenticated', user }
        if (mountedRef.current && requestId === requestIdRef.current) setSnapshot(next)
        return next
      } catch (error) {
        const next = rejectedSnapshot(error)
        if (mountedRef.current && requestId === requestIdRef.current) setSnapshot(next)
        return next
      }
    },
    [],
  )

  useEffect(() => {
    mountedRef.current = true
    void applyCurrentUserRequest(loadBootstrapUser())
    return () => {
      mountedRef.current = false
    }
  }, [applyCurrentUserRequest])

  const recheck = useCallback(() => {
    setSnapshot({ status: 'checking', user: null })
    return applyCurrentUserRequest(getCurrentUser())
  }, [applyCurrentUserRequest])

  const login = useCallback(async (input: LoginRequest, signal?: AbortSignal) => {
    const requestId = ++requestIdRef.current

    try {
      const user = await requestLogin(input, signal)
      if (mountedRef.current && requestId === requestIdRef.current) {
        setSnapshot({ status: 'authenticated', user })
      }
      return user
    } catch (error) {
      if (signal?.aborted) throw error
      if (mountedRef.current && requestId === requestIdRef.current) {
        setSnapshot(rejectedSnapshot(error))
      }
      throw error
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ ...snapshot, login, recheck }),
    [login, recheck, snapshot],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
