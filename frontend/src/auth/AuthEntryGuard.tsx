import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './auth-context'
import styles from './AuthEntryGuard.module.css'

/** 인증 여부 확인 전에는 인증 폼을 숨기고, 로그인 사용자는 홈으로 돌려보낸다. */
export function AuthEntryGuard({ children }: { children: ReactNode }) {
  const { status } = useAuth()

  if (status === 'checking') {
    return (
      <main className={styles.checking}>
        <span role="status">로그인 상태를 확인하고 있습니다.</span>
      </main>
    )
  }
  if (status === 'authenticated') {
    return <Navigate to="/home" replace />
  }
  return children
}
