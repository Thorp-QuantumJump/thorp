import { useEffect, useRef, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '@/auth/auth-context'
import { consumeReturnTarget } from '@/auth/returnTarget'
import { AuthStatus } from '@/components/auth/AuthStatus'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import formStyles from '@/components/auth/AuthForm.module.css'

/**
 * 연결된 소셜 계정으로 로그인한 뒤 서버가 보내는 자리(`/auth/social/complete`).
 *
 * <p>서버가 이미 세션을 만들었다. 여기서는 현재 사용자를 다시 확인해 로그인 상태로 바꾸고, 제공자로
 * 떠나기 전에 적어 둔 자리로 한 번만 돌아간다(FR-011). 확인하지 못하면 로그인했다고 단정하지 않는다(FR-012).
 */
export function SocialCompletePage() {
  const { recheck } = useAuth()
  const startedRef = useRef(false)
  const [result, setResult] = useState<'checking' | 'failed' | string>('checking')

  useEffect(() => {
    if (startedRef.current) return
    startedRef.current = true
    void recheck().then((snapshot) => {
      setResult(snapshot.status === 'authenticated' ? consumeReturnTarget() : 'failed')
    })
  }, [recheck])

  if (result !== 'checking' && result !== 'failed') {
    return <Navigate to={result} replace />
  }

  return (
    <SocialAuthLayout
      eyebrow="SOCIAL LOGIN"
      title="로그인을 마무리하고 있어요."
      description="잠시만 기다려 주세요. 하던 화면으로 바로 돌아갑니다."
      panelTitle="로그인 확인"
    >
      {result === 'checking' ? (
        <AuthStatus message="로그인 상태를 확인하고 있습니다." tone="info" />
      ) : (
        <>
          <AuthStatus
            message="로그인을 확인하지 못했습니다. 다시 로그인해 주세요."
            tone="error"
          />
          <Link className={formStyles.submitButton} to="/login">
            로그인 화면으로
          </Link>
        </>
      )}
    </SocialAuthLayout>
  )
}
