import { useEffect, useState, type ReactNode } from 'react'
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

/** 현재 사용자 확인이 끝난 경우에만 내 기록 화면을 노출한다. */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'checking') {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 상태를 확인하고 있습니다.</span>
      </main>
    )
  }
  if (status === 'authenticated') return children

  const target = `${location.pathname}${location.search}${location.hash}`
  return <LoginRedirect target={target} />
}
