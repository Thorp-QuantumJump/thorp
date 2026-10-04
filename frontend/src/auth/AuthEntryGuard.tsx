import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './auth-context'
import styles from './AuthEntryGuard.module.css'
import { consumeReturnTarget, DEFAULT_RETURN_TARGET } from './returnTarget'

function AuthenticatedEntryRedirect({ resume }: { resume: boolean }) {
  const handledRef = useRef(false)
  const [target, setTarget] = useState<string | null>(null)

  useEffect(() => {
    if (handledRef.current) return
    handledRef.current = true
    const savedTarget = consumeReturnTarget()
    setTarget(resume ? savedTarget : DEFAULT_RETURN_TARGET)
  }, [resume])

  if (!target) {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 완료 화면으로 이동하고 있습니다.</span>
      </main>
    )
  }
  return <Navigate to={target} replace />
}

/** 인증 여부 확인 전에는 인증 폼을 숨기고, 로그인 사용자는 돌아갈 화면(없으면 전략 진단 첫 단계)으로 보낸다. */
export function AuthEntryGuard({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const previousStatusRef = useRef(status)

  useEffect(() => {
    previousStatusRef.current = status
  }, [status])

  if (status === 'checking') {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 상태를 확인하고 있습니다.</span>
      </main>
    )
  }
  if (status === 'authenticated') {
    const previousStatus = previousStatusRef.current
    const shouldResume =
      previousStatus === 'anonymous' ||
      previousStatus === 'expired' ||
      previousStatus === 'error' ||
      previousStatus === 'logoutUnconfirmed'
    return <AuthenticatedEntryRedirect resume={shouldResume} />
  }
  return children
}
