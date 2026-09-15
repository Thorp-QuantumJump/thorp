import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  getCurrentUser,
  login as requestLogin,
  logout as requestLogout,
} from '@/api/auth'
import { ApiClientError, subscribeAuthenticationRequired } from '@/api/client'
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

function rejectedSnapshot(error: unknown, previousStatus?: AuthSnapshot['status']): AuthSnapshot {
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
    const status =
      previousStatus === 'authenticated' || previousStatus === 'expired'
        ? 'expired'
        : 'anonymous'
    return { status, user: null }
  }
  if (previousStatus === 'logoutUnconfirmed') {
    return { status: 'logoutUnconfirmed', user: null }
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
  const currentUserAbortRef = useRef<AbortController | null>(null)
  const snapshotRef = useRef(snapshot)

  const commitSnapshot = useCallback((next: AuthSnapshot) => {
    snapshotRef.current = next
    if (mountedRef.current) setSnapshot(next)
  }, [])

  const applyCurrentUserRequest = useCallback(
    async (request: Promise<AuthenticatedUser>): Promise<AuthSnapshot> => {
      const requestId = ++requestIdRef.current

      try {
        const user = await request
        const next: AuthSnapshot = { status: 'authenticated', user }
        if (mountedRef.current && requestId === requestIdRef.current) commitSnapshot(next)
        return next
      } catch (error) {
        const next = rejectedSnapshot(error, snapshotRef.current.status)
        if (mountedRef.current && requestId === requestIdRef.current) commitSnapshot(next)
        return next
      }
    },
    [commitSnapshot],
  )

  useEffect(() => {
    mountedRef.current = true
    void applyCurrentUserRequest(loadBootstrapUser())
    return () => {
      mountedRef.current = false
      currentUserAbortRef.current?.abort()
    }
  }, [applyCurrentUserRequest])

  const recheck = useCallback(async (): Promise<AuthSnapshot> => {
    const previousStatus = snapshotRef.current.status
    const requestId = ++requestIdRef.current
    currentUserAbortRef.current?.abort()
    const controller = new AbortController()
    currentUserAbortRef.current = controller
    commitSnapshot({ status: 'checking', user: null })

    try {
      const user = await getCurrentUser(controller.signal)
      const next: AuthSnapshot = { status: 'authenticated', user }
      if (mountedRef.current && requestId === requestIdRef.current) commitSnapshot(next)
      return next
    } catch (error) {
      if (controller.signal.aborted || requestId !== requestIdRef.current) {
        return snapshotRef.current
      }
      const next = rejectedSnapshot(error, previousStatus)
      if (mountedRef.current) commitSnapshot(next)
      return next
    } finally {
      if (currentUserAbortRef.current === controller) currentUserAbortRef.current = null
    }
  }, [commitSnapshot])

  const login = useCallback(async (input: LoginRequest, signal?: AbortSignal) => {
    const requestId = ++requestIdRef.current
    currentUserAbortRef.current?.abort()

    try {
      const user = await requestLogin(input, signal)
      if (mountedRef.current && requestId === requestIdRef.current) {
        commitSnapshot({ status: 'authenticated', user })
      }
      return user
    } catch (error) {
      if (signal?.aborted) throw error
      if (mountedRef.current && requestId === requestIdRef.current) {
        commitSnapshot(rejectedSnapshot(error))
      }
      throw error
    }
  }, [commitSnapshot])

  const logout = useCallback(async (signal?: AbortSignal) => {
    const requestId = ++requestIdRef.current
    currentUserAbortRef.current?.abort()
    commitSnapshot({ status: 'logoutUnconfirmed', user: null })

    try {
      await requestLogout(signal)
      if (mountedRef.current && requestId === requestIdRef.current) {
        commitSnapshot({ status: 'anonymous', user: null })
      }
    } catch (error) {
      if (!signal?.aborted && mountedRef.current && requestId === requestIdRef.current) {
        commitSnapshot({ status: 'logoutUnconfirmed', user: null })
      }
      throw error
    }
  }, [commitSnapshot])

  useEffect(() => subscribeAuthenticationRequired(() => {
    if (snapshotRef.current.status !== 'authenticated') return
    requestIdRef.current += 1
    currentUserAbortRef.current?.abort()
    commitSnapshot({ status: 'expired', user: null })
  }), [commitSnapshot])

  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') void recheck()
    }
    const handlePageShow = (event: PageTransitionEvent) => {
      if (event.persisted) void recheck()
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pageshow', handlePageShow)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('pageshow', handlePageShow)
    }
  }, [recheck])

  const value = useMemo<AuthContextValue>(
    () => ({ ...snapshot, login, logout, recheck }),
    [login, logout, recheck, snapshot],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
