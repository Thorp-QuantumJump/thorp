import { UserPlus } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { ApiClientError } from '@/api/client'
import { cancelPendingSocial, socialSignup } from '@/api/socialAuth'
import { useAuth } from '@/auth/auth-context'
import { routeOfStage, usePendingSocial } from '@/auth/usePendingSocial'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import { SocialPendingFallback } from '@/components/auth/SocialPendingFallback'
import { socialProviderNames } from '@/components/auth/socialProviders'
import formStyles from '@/components/auth/AuthForm.module.css'
import pageStyles from './SignupPage.module.css'
import type { PendingSocialView } from '@/types/auth'

type Errors = Partial<Record<'nickname', string>>

/**
 * 처음 오는 소셜 사용자의 추가 정보(`/signup/social`, spec 014 US6, FR-023).
 *
 * <p>이메일은 제공자에게 받은 값이라 고칠 수 없게 보여 주고, 닉네임만 받는다(휴대폰 번호는 묻지 않는다, spec 014 2026-10-02). 비밀번호와
 * 이메일 인증번호는 묻지 않는다. 가입이 끝나면 로그인 상태가 되고 `AuthEntryGuard`가 원래 자리로 보낸다.
 */
export function SocialSignupPage() {
  const { state, reload } = usePendingSocial()

  if (state.status === 'ready' && state.pending.stage !== 'SIGNUP') {
    return <Navigate to={routeOfStage(state.pending.stage)} replace />
  }

  return (
    <SocialAuthLayout
      eyebrow="ALMOST DONE"
      title="가입까지 한 걸음 남았어요."
      description="Thorp에서 쓸 닉네임만 확인해 주세요."
      panelTitle="추가 정보"
      panelDescription="소셜 계정으로 가입하면 비밀번호를 따로 만들지 않아도 됩니다."
    >
      {state.status === 'ready' ? (
        <SocialSignupForm pending={state.pending} />
      ) : (
        <SocialPendingFallback state={state} onRetry={reload} />
      )}
    </SocialAuthLayout>
  )
}

function SocialSignupForm({ pending }: { pending: PendingSocialView }) {
  const navigate = useNavigate()
  const { completeSignIn } = useAuth()
  const nicknameRef = useRef<HTMLInputElement>(null)
  const requestRef = useRef<AbortController | null>(null)
  const [nickname, setNickname] = useState(pending.suggestedNickname ?? '')
  const [errors, setErrors] = useState<Errors>({})
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [statusMessage, setStatusMessage] = useState('')

  useEffect(() => () => requestRef.current?.abort(), [])

  const validate = (): Errors => {
    const next: Errors = {}
    const trimmed = nickname.trim()
    if (!trimmed) next.nickname = '닉네임을 입력해 주세요.'
    else if ([...trimmed].length < 2 || [...trimmed].length > 20) {
      next.nickname = '닉네임은 2~20자로 입력해 주세요.'
    }
    return next
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (isSubmitting) return

    const nextErrors = validate()
    setErrors(nextErrors)
    setStatusMessage('')
    if (nextErrors.nickname) {
      nicknameRef.current?.focus()
      return
    }

    const controller = new AbortController()
    requestRef.current?.abort()
    requestRef.current = controller
    setIsSubmitting(true)
    try {
      const user = await socialSignup({ nickname }, controller.signal)
      setStatusMessage('가입했습니다. 하던 화면으로 이동합니다.')
      // 로그인 상태가 되면 인증 화면 가드가 소셜 로그인 전에 있던 자리로 보낸다.
      completeSignIn(user)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiClientError) {
        if (error.is('SOCIAL_LINK_REQUIRED')) {
          navigate('/auth/social/link', { replace: true })
          return
        }
        if (error.is('SOCIAL_PROVIDER_ALREADY_LINKED')) {
          navigate(`/auth/social/already-linked?provider=${pending.provider}`, { replace: true })
          return
        }
        if (error.is('SOCIAL_PENDING_NOT_FOUND')) {
          setStatusMessage('소셜 로그인 시간이 지났습니다. 처음부터 다시 시도해 주세요.')
          return
        }
        const fieldError = error.fieldErrors.find((item) => item.field === 'nickname')
        if (fieldError) {
          setErrors({ nickname: fieldError.message })
          nicknameRef.current?.focus()
          return
        }
        setStatusMessage(error.message)
      } else {
        setStatusMessage('가입 요청을 처리하지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null
        setIsSubmitting(false)
      }
    }
  }

  const handleCancel = async () => {
    try {
      await cancelPendingSocial()
    } finally {
      navigate('/login', { replace: true })
    }
  }

  return (
    <form className={pageStyles.form} onSubmit={handleSubmit} noValidate>
      <AuthField
        label="이메일"
        type="email"
        name="email"
        value={pending.email}
        readOnly
        hint={`${socialProviderNames[pending.provider]}에서 받은 이메일입니다. 바꿀 수 없습니다.`}
      />
      <AuthField
        ref={nicknameRef}
        label="닉네임"
        name="nickname"
        autoComplete="nickname"
        placeholder="2~20자"
        value={nickname}
        error={errors.nickname}
        disabled={isSubmitting}
        onChange={(event) => {
          setNickname(event.target.value)
          setErrors((current) => ({ ...current, nickname: undefined }))
        }}
      />
      <AuthStatus
        message={statusMessage}
        tone={statusMessage.startsWith('가입했습니다') ? 'success' : 'error'}
      />
      <div className={pageStyles.formActions}>
        <button type="submit" className={formStyles.submitButton} disabled={isSubmitting}>
          <UserPlus size={18} aria-hidden="true" />
          {isSubmitting ? '가입하는 중…' : '가입하기'}
        </button>
        <button
          type="button"
          className={formStyles.secondaryButton}
          disabled={isSubmitting}
          onClick={() => void handleCancel()}
        >
          가입하지 않기
        </button>
      </div>
    </form>
  )
}
