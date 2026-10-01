import { Link2 } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiClientError } from '@/api/client'
import {
  cancelPendingSocial,
  declineSocialLink,
  sendSocialVerificationCode,
  socialLink,
  startSocialLogin,
} from '@/api/socialAuth'
import { useAuth } from '@/auth/auth-context'
import { routeOfStage, usePendingSocial } from '@/auth/usePendingSocial'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import { ProviderMark } from '@/components/auth/SocialLoginButtons'
import { SocialPendingFallback } from '@/components/auth/SocialPendingFallback'
import { socialProviderNames } from '@/components/auth/socialProviders'
import formStyles from '@/components/auth/AuthForm.module.css'
import socialStyles from '@/components/auth/SocialLoginButtons.module.css'
import pageStyles from './SignupPage.module.css'
import styles from './SocialLinkPage.module.css'
import type { PendingSocialView, SocialLinkRequest, SocialProviderId } from '@/types/auth'

type Status = { message: string; tone: 'info' | 'success' | 'error' }

const socialMethods: Record<string, SocialProviderId> = {
  SOCIAL_KAKAO: 'kakao',
  SOCIAL_GOOGLE: 'google',
  SOCIAL_NAVER: 'naver',
}

/**
 * 같은 이메일·휴대폰 번호의 기존 계정에 소셜 계정을 연결한다(`/auth/social/link`, spec 014 US7, FR-024).
 *
 * <p><b>자동으로 합치지 않는다.</b> 사용자가 연결을 고르고, 그 기존 계정의 주인임을 확인해야 연결된다.
 * 확인 방법은 서버가 그 계정에 쓸 수 있다고 알려준 것만 보여 준다.
 */
export function SocialLinkPage() {
  const { state, reload } = usePendingSocial()

  if (state.status === 'ready' && state.pending.stage !== 'LINK') {
    return <Navigate to={routeOfStage(state.pending.stage)} replace />
  }

  const reason = state.status === 'ready' ? state.pending.linkReason : null

  return (
    <SocialAuthLayout
      eyebrow="ACCOUNT FOUND"
      title={
        reason === 'PHONE' ? '이 번호로 가입된 계정이 있어요.' : '이미 가입된 계정이 있어요.'
      }
      description="기존 계정의 주인이 맞는지 확인하면 소셜 계정을 연결해 함께 쓸 수 있습니다. 확인하기 전에는 아무것도 연결하지 않습니다."
      panelTitle="계정 연결 확인"
    >
      {state.status === 'ready' ? (
        <LinkOptions pending={state.pending} />
      ) : (
        <SocialPendingFallback state={state} onRetry={reload} />
      )}
    </SocialAuthLayout>
  )
}

function LinkOptions({ pending }: { pending: PendingSocialView }) {
  const navigate = useNavigate()
  const { completeSignIn } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const passwordRef = useRef<HTMLInputElement>(null)
  const codeRef = useRef<HTMLInputElement>(null)
  const requestRef = useRef<AbortController | null>(null)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [codeSent, setCodeSent] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [status, setStatus] = useState<Status>({ message: '', tone: 'info' })

  // 다른 소셜 계정으로 확인하러 갔다가 돌아온 결과(`?social=`)를 한 번 알리고 주소에서 지운다.
  useEffect(() => {
    const result = searchParams.get('social')
    if (!result) return
    setStatus({
      message:
        result === 'cancelled'
          ? '소셜 계정 확인을 취소했습니다. 다른 방법으로 확인해 주세요.'
          : '그 소셜 계정으로는 이 계정을 확인하지 못했습니다. 다른 방법으로 확인해 주세요.',
      tone: 'error',
    })
    setSearchParams({}, { replace: true })
  }, [searchParams, setSearchParams])

  useEffect(() => () => requestRef.current?.abort(), [])

  const providerName = socialProviderNames[pending.provider]
  const methods = pending.linkMethods

  const submit = async (input: SocialLinkRequest, key: string) => {
    if (busy) return
    const controller = new AbortController()
    requestRef.current = controller
    setBusy(key)
    setStatus({ message: '', tone: 'info' })
    try {
      const user = await socialLink(input, controller.signal)
      setStatus({ message: `${providerName} 계정을 연결했습니다. 하던 화면으로 이동합니다.`, tone: 'success' })
      completeSignIn(user)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiClientError) {
        if (error.is('SOCIAL_PROVIDER_ALREADY_LINKED')) {
          navigate(`/auth/social/already-linked?provider=${pending.provider}`, { replace: true })
          return
        }
        if (error.is('INVALID_CREDENTIALS')) {
          setStatus({ message: '비밀번호를 다시 확인해 주세요.', tone: 'error' })
          passwordRef.current?.focus()
          return
        }
        if (error.code?.startsWith('EMAIL_VERIFICATION_')) {
          setStatus({ message: error.message, tone: 'error' })
          codeRef.current?.focus()
          return
        }
        setStatus({ message: error.message, tone: 'error' })
      } else {
        setStatus({ message: '계정을 연결하지 못했습니다. 다시 시도해 주세요.', tone: 'error' })
      }
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null
        setBusy(null)
      }
    }
  }

  const handlePassword = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!password) {
      setStatus({ message: '비밀번호를 입력해 주세요.', tone: 'error' })
      passwordRef.current?.focus()
      return
    }
    void submit({ method: 'PASSWORD', password }, 'password')
  }

  const handleSendCode = async () => {
    if (busy) return
    setBusy('send')
    setStatus({ message: '인증번호를 보내고 있습니다.', tone: 'info' })
    try {
      await sendSocialVerificationCode()
      setCodeSent(true)
      setStatus({
        message: pending.linkTargetMaskedEmail
          ? `${pending.linkTargetMaskedEmail}로 인증번호를 보냈습니다. 3분 안에 입력해 주세요.`
          : '가입할 때 쓴 이메일로 인증번호를 보냈습니다. 3분 안에 입력해 주세요.',
        tone: 'success',
      })
      codeRef.current?.focus()
    } catch (error) {
      setStatus({
        message:
          error instanceof ApiClientError
            ? error.message
            : '인증번호를 보내지 못했습니다. 다시 시도해 주세요.',
        tone: 'error',
      })
    } finally {
      setBusy(null)
    }
  }

  const handleCode = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!/^\d{6}$/.test(code)) {
      setStatus({ message: '6자리 숫자를 입력해 주세요.', tone: 'error' })
      codeRef.current?.focus()
      return
    }
    void submit({ method: 'EMAIL_CODE', verificationCode: code }, 'code')
  }

  const handleDecline = async () => {
    if (busy) return
    setBusy('decline')
    try {
      await declineSocialLink()
      // 번호가 겹쳐 온 연결은 가입으로 돌아가 다른 번호를 넣을 수 있다. 이메일이 겹쳐 온 연결은 끝난다.
      navigate(pending.linkReason === 'PHONE' ? '/signup/social' : '/login', { replace: true })
    } catch {
      await cancelPendingSocial().catch(() => undefined)
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className={styles.options}>
      {/*
        번호가 겹쳐 온 연결이면 서버가 그 계정의 이메일을 주지 않는다(spec 014 FR-024). 요청한 사람이 그 번호의 주인인지 아직
        모르므로, 남의 번호로 그 계정의 이메일 일부를 알아내지 못하게 한다. 인증번호는 서버가 그 계정 이메일로 보낸다.
      */}
      {pending.linkTargetMaskedEmail ? (
        <p className={styles.target}>
          <span>기존 계정</span>
          <strong>{pending.linkTargetMaskedEmail}</strong>
        </p>
      ) : null}
      <p className={styles.explain}>
        {providerName} 계정({pending.email})을 이 계정에 연결합니다. 아래 방법 중 하나로 이 계정의
        주인인지 확인해 주세요.
      </p>

      {methods.includes('VERIFIED_EMAIL') ? (
        <section className={styles.method} aria-labelledby="link-verified">
          <h3 id="link-verified">이메일 확인을 마쳤어요</h3>
          <p>방금 인증번호로 이 계정의 이메일을 확인했습니다.</p>
          <button
            type="button"
            className={formStyles.submitButton}
            disabled={busy !== null}
            onClick={() => void submit({ method: 'VERIFIED_EMAIL' }, 'verified')}
          >
            <Link2 size={18} aria-hidden="true" />
            {busy === 'verified' ? '연결하는 중…' : '이 계정에 연결하기'}
          </button>
        </section>
      ) : null}

      {methods.includes('PASSWORD') ? (
        <section className={styles.method} aria-labelledby="link-password">
          <h3 id="link-password">기존 비밀번호로 확인</h3>
          <form className={pageStyles.form} onSubmit={handlePassword} noValidate>
            <AuthField
              ref={passwordRef}
              label="비밀번호"
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              disabled={busy !== null}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button type="submit" className={formStyles.submitButton} disabled={busy !== null}>
              {busy === 'password' ? '확인하는 중…' : '확인하고 연결하기'}
            </button>
          </form>
        </section>
      ) : null}

      {methods.includes('EMAIL_CODE') ? (
        <section className={styles.method} aria-labelledby="link-code">
          <h3 id="link-code">기존 계정 이메일로 확인</h3>
          <p>
            {pending.linkTargetMaskedEmail
              ? '비밀번호가 기억나지 않으면 기존 계정 이메일로 인증번호를 받아 확인할 수 있습니다.'
              : '비밀번호가 기억나지 않으면 그 계정에 가입할 때 쓴 이메일로 인증번호를 받아 확인할 수 있습니다.'}
          </p>
          <form className={pageStyles.form} onSubmit={handleCode} noValidate>
            <AuthField
              ref={codeRef}
              label="인증번호"
              name="verificationCode"
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="6자리 숫자"
              maxLength={6}
              value={code}
              disabled={!codeSent || busy !== null}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
              action={
                <button
                  type="button"
                  className={formStyles.secondaryButton}
                  disabled={busy !== null}
                  onClick={() => void handleSendCode()}
                >
                  {codeSent ? '다시 받기' : '인증번호 받기'}
                </button>
              }
            />
            <button
              type="submit"
              className={formStyles.submitButton}
              disabled={!codeSent || busy !== null}
            >
              {busy === 'code' ? '확인하는 중…' : '확인하고 연결하기'}
            </button>
          </form>
        </section>
      ) : null}

      {methods.some((method) => method in socialMethods) ? (
        <section className={styles.method} aria-labelledby="link-social">
          <h3 id="link-social">이미 연결된 소셜 계정으로 확인</h3>
          <ul className={socialStyles.list}>
            {methods
              .filter((method) => method in socialMethods)
              .map((method) => {
                const provider = socialMethods[method]
                return (
                  <li key={method}>
                    <button
                      type="button"
                      className={socialStyles.button}
                      data-provider={provider}
                      disabled={busy !== null}
                      onClick={() => startSocialLogin(provider, { intent: 'link' })}
                    >
                      <ProviderMark provider={provider} />
                      <span>{socialProviderNames[provider]}로 확인하기</span>
                    </button>
                  </li>
                )
              })}
          </ul>
        </section>
      ) : null}

      <AuthStatus message={status.message} tone={status.tone} />

      <button
        type="button"
        className={formStyles.secondaryButton}
        disabled={busy !== null}
        onClick={() => void handleDecline()}
      >
        {pending.linkReason === 'PHONE' ? '연결하지 않고 다른 번호로 가입하기' : '연결하지 않기'}
      </button>
    </div>
  )
}
