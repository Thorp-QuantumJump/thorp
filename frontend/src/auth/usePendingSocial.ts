import { useCallback, useEffect, useState } from 'react'
import { getPendingSocial } from '@/api/socialAuth'
import { ApiClientError } from '@/api/client'
import type { PendingSocialView } from '@/types/auth'

export type PendingSocialState =
  | { status: 'loading' }
  | { status: 'ready'; pending: PendingSocialView }
  /** 대기 상태가 없거나 만료됐거나 이미 썼다(FR-025). 처음부터 다시 시작해야 한다. */
  | { status: 'missing' }
  | { status: 'error'; message: string }

/**
 * 서버 세션의 소셜 가입·연결 대기 상태를 읽는다.
 *
 * <p>값은 이 화면의 메모리에만 둔다. 새로고침하면 서버에서 다시 읽는다 — 저장소에 남기지 않는다.
 */
export function usePendingSocial(): {
  state: PendingSocialState
  reload: () => void
  replace: (pending: PendingSocialView) => void
} {
  const [state, setState] = useState<PendingSocialState>({ status: 'loading' })
  const [version, setVersion] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    getPendingSocial(controller.signal)
      .then((pending) => {
        if (!controller.signal.aborted) setState({ status: 'ready', pending })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (error instanceof ApiClientError && error.is('SOCIAL_PENDING_NOT_FOUND')) {
          setState({ status: 'missing' })
          return
        }
        setState({
          status: 'error',
          message:
            error instanceof ApiClientError
              ? error.message
              : '소셜 로그인 진행 상태를 확인하지 못했습니다. 다시 시도해 주세요.',
        })
      })
    return () => controller.abort()
  }, [version])

  const reload = useCallback(() => {
    setState({ status: 'loading' })
    setVersion((current) => current + 1)
  }, [])

  const replace = useCallback((pending: PendingSocialView) => {
    setState({ status: 'ready', pending })
  }, [])

  return { state, reload, replace }
}

/** 대기 상태의 단계가 정한 화면 주소. 화면이 단계와 맞지 않으면 여기로 옮긴다. */
export function routeOfStage(stage: PendingSocialView['stage']): string {
  switch (stage) {
    case 'EMAIL_VERIFICATION':
      return '/auth/social/verify-email'
    case 'SIGNUP':
      return '/signup/social'
    case 'LINK':
      return '/auth/social/link'
  }
}
