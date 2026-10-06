import { MailCheck } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { ApiClientError } from '@/api/client'
import { cancelPendingSocial, confirmSocialEmail, sendSocialVerificationCode } from '@/api/socialAuth'
import { routeOfStage, usePendingSocial } from '@/auth/usePendingSocial'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import { SocialPendingFallback } from '@/components/auth/SocialPendingFallback'
import { socialProviderNames } from '@/components/auth/socialProviders'
import formStyles from '@/components/auth/AuthForm.module.css'
import pageStyles from './SignupPage.module.css'
import type { PendingSocialView } from '@/types/auth'

/**
 * 제공자가 확인해 주지 않은 이메일을 Thorp 인증번호로 확인한다(`/auth/social/verify-email`, FR-022).
 *
 * <p>네이버는 확인 여부를 알려주지 않아 언제나 이 화면을 지난다. 확인이 끝나면 가입 또는 계정 연결로
 * 이어지고, 그 전에는 계정을 만들거나 연결하거나 로그인하지 않는다.
 */
export function SocialVerifyEmailPage() {
  const { state, reload } = usePendingSocial()

  if (state.status === 'ready' && state.pending.stage !== 'EMAIL_VERIFICATION') {
    return <Navigate to={routeOfStage(state.pending.stage)} replace />
  }

  return (
    <SocialAuthLayout
      eyebrow="CHECK YOUR EMAIL"
      title="이메일을 한 번 확인할게요."
      description="받은 이메일이 본인의 것인지 확인한 뒤 가입을 이어 갑니다."
      panelTitle="이메일 확인"
    >
      {state.status === 'ready' ? (
        <VerifyForm pending={state.pending} reload={reload} />
      ) : (
        <SocialPendingFallback state={state} onRetry={reload} />
      )}
    </SocialAuthLayout>
  )
}

/** 서버가 대기 상태를 버리는 오류. 상한을 넘었거나(대기 상태 삭제) 이미 없다. */
function pendingGone(error: unknown) {
  return (
    error instanceof ApiClientError &&
    (error.is('SOCIAL_VERIFICATION_LIMIT') || error.is('SOCIAL_PENDING_NOT_FOUND'))
  )
}

function VerifyForm({ pending, reload }: { pending: PendingSocialView; reload: () => void }) {
  const navigate = useNavigate()
  const codeRef = useRef<HTMLInputElement>(null)
  const requestRef = useRef<AbortController | null>(null)
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string>()
  const [sent, setSent] = useState(false)
  const [pendingAction, setPendingAction] = useState<'send' | 'confirm' | null>(null)
  const [status, setStatus] = useState<{ message: string; tone: 'info' | 'success' | 'error' }>({
    message: '',
    tone: 'info',
  })

  useEffect(() => () => requestRef.current?.abort(), [])

  const handleSend = async () => {
    if (pendingAction) return
    const controller = new AbortController()
    requestRef.current = controller
    setPendingAction('send')
    setStatus({ message: '인증번호를 보내고 있습니다.', tone: 'info' })
    try {
      await sendSocialVerificationCode(controller.signal)
      setSent(true)
      setStatus({ message: '인증번호를 보냈습니다. 3분 안에 입력해 주세요.', tone: 'success' })
      codeRef.current?.focus()
    } catch (error) {
      if (controller.signal.aborted) return
      if (pendingGone(error)) {
        // 폼을 남겨 두면 누를 때마다 "찾을 수 없음"만 돌아온다 — 다시 읽어 "처음부터 다시" 안내로 바꾼다.
        reload()
        return
      }
      setStatus({
        message:
          error instanceof ApiClientError
            ? error.message
            : '인증번호를 보내지 못했습니다. 다시 시도해 주세요.',
        tone: 'error',
      })
    } finally {
      setPendingAction(null)
    }
  }

  const handleConfirm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pendingAction) return
    if (!/^\d{6}$/.test(code)) {
      setCodeError('6자리 숫자를 입력해 주세요.')
      codeRef.current?.focus()
      return
    }
    const controller = new AbortController()
    requestRef.current = controller
    setPendingAction('confirm')
    setStatus({ message: '', tone: 'info' })
    try {
      const next = await confirmSocialEmail(code, controller.signal)
      navigate(routeOfStage(next.stage), { replace: true })
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiClientError && error.is('SOCIAL_PROVIDER_ALREADY_LINKED')) {
        navigate(`/auth/social/already-linked?provider=${pending.provider}`, { replace: true })
        return
      }
      if (pendingGone(error)) {
        reload()
        return
      }
      setCodeError(
        error instanceof ApiClientError
          ? error.message
          : '인증번호를 확인하지 못했습니다. 다시 시도해 주세요.',
      )
      codeRef.current?.focus()
    } finally {
      setPendingAction(null)
    }
  }

  return (
    <form className={pageStyles.form} onSubmit={handleConfirm} noValidate>
      <AuthField
        label="이메일"
        type="email"
        name="email"
        value={pending.email}
        readOnly
        hint={`${socialProviderNames[pending.provider]}에서 받은 이메일입니다.`}
        action={
          <button
            type="button"
            className={formStyles.secondaryButton}
            disabled={pendingAction !== null}
            onClick={() => void handleSend()}
          >
            {sent ? '다시 받기' : '인증번호 받기'}
          </button>
        }
      />
      <AuthField
        ref={codeRef}
        label="인증번호"
        name="verificationCode"
        inputMode="numeric"
        autoComplete="one-time-code"
        placeholder="6자리 숫자"
        maxLength={6}
        value={code}
        error={codeError}
        disabled={!sent || pendingAction !== null}
        onChange={(event) => {
          setCode(event.target.value.replace(/\D/g, ''))
          setCodeError(undefined)
        }}
      />
      <AuthStatus message={status.message} tone={status.tone} />
      <div className={pageStyles.formActions}>
        <button
          type="submit"
          className={formStyles.submitButton}
          disabled={!sent || pendingAction !== null}
        >
          <MailCheck size={18} aria-hidden="true" />
          {pendingAction === 'confirm' ? '확인하는 중…' : '확인하고 계속하기'}
        </button>
        <button
          type="button"
          className={formStyles.secondaryButton}
          disabled={pendingAction !== null}
          onClick={() => {
            void cancelPendingSocial().finally(() => navigate('/login', { replace: true }))
          }}
        >
          그만두기
        </button>
      </div>
    </form>
  )
}
