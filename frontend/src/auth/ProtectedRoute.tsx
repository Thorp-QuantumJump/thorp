import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './auth-context'
import styles from './AuthEntryGuard.module.css'
import { saveReturnTarget } from './returnTarget'

function LoginRedirect({ target }: { target: string }) {
  const [isSaved, setIsSaved] = useState(false)

  useEffect(() => {
    saveReturnTarget(target)
    setIsSaved(true)
  }, [target])

  if (!isSaved) {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 화면으로 이동하고 있습니다.</span>
      </main>
    )
  }
  return <Navigate to="/login" replace />
}

/** 현재 사용자 확인이 끝난 경우에만 보호된 화면을 노출한다. */
export function ProtectedRoute({
  children,
  recoveryTitle = '내 기록을 잠시 숨겼습니다.',
}: {
  children: ReactNode
  recoveryTitle?: string
}) {
  const { status, logout, recheck } = useAuth()
  const location = useLocation()
  const target = `${location.pathname}${location.search}${location.hash}`
  const recheckTargetRef = useRef<string | null>(null)
  const [verifiedTarget, setVerifiedTarget] = useState<string | null>(null)

  useEffect(() => {
    if (status !== 'authenticated' || recheckTargetRef.current === target) return

    recheckTargetRef.current = target
    void recheck().then((next) => {
      if (next.status === 'authenticated') setVerifiedTarget(target)
      else recheckTargetRef.current = null
    })
  }, [recheck, status, target])

  if (status === 'checking' || (status === 'authenticated' && verifiedTarget !== target)) {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 상태를 확인하고 있습니다.</span>
      </main>
    )
  }
  if (status === 'authenticated') return children

  if (status === 'error' || status === 'logoutUnconfirmed') {
    return (
      <main className={styles.checking}>
        <section className={styles.recovery} aria-labelledby="auth-recovery-title">
          <h1 id="auth-recovery-title">{recoveryTitle}</h1>
          <p role={status === 'logoutUnconfirmed' ? 'alert' : 'status'}>
            {status === 'logoutUnconfirmed'
              ? '로그아웃 결과를 확인하지 못했습니다.'
              : '로그인 상태를 확인하지 못했습니다.'}
          </p>
          <div className={styles.actions}>
            {status === 'logoutUnconfirmed' && (
              <button type="button" onClick={() => void logout().catch(() => undefined)}>
                로그아웃 다시 시도
              </button>
            )}
            <button type="button" onClick={() => void recheck()}>
              로그인 상태 다시 확인
            </button>
          </div>
        </section>
      </main>
    )
  }

  return <LoginRedirect target={target} />
}
