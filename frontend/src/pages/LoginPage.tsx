import { ArrowLeft, LogIn } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { normalizeEmail } from '@/api/auth'
import { ApiClientError } from '@/api/client'
import { useAuth } from '@/auth/auth-context'
import { clearLoginEmail, readLoginEmail, saveLoginEmail } from '@/auth/loginEmail'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import formStyles from '@/components/auth/AuthForm.module.css'
import styles from './LoginPage.module.css'
import pageStyles from './SignupPage.module.css'

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function readSignupEmail(state: unknown): string {
  if (typeof state !== 'object' || state === null || !('signupEmail' in state)) return ''
  return typeof state.signupEmail === 'string' ? normalizeEmail(state.signupEmail) : ''
}

type LoginErrors = Partial<Record<'email' | 'password', string>>

/** 이메일·비밀번호 로그인 요청을 전역 인증 상태와 연결한다. */
export function LoginPage() {
  const location = useLocation()
  const { login } = useAuth()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const requestRef = useRef<AbortController | null>(null)
  const [email, setEmail] = useState(
    () => readSignupEmail(location.state) || readLoginEmail(),
  )
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<LoginErrors>({})
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [statusMessage, setStatusMessage] = useState('')

  // 쳐 둔 주소를 적어 둔다. 비밀번호는 넣지 않는다(`loginEmail` 참고).
  useEffect(() => {
    saveLoginEmail(email)
  }, [email])

  useEffect(() => {
    headingRef.current?.focus()
    return () => {
      requestRef.current?.abort()
      requestRef.current = null
    }
  }, [])

  const validate = (): LoginErrors => {
    const next: LoginErrors = {}
    const normalizedEmail = normalizeEmail(email)

    if (!normalizedEmail) next.email = '이메일을 입력해 주세요.'
    else if (!emailPattern.test(normalizedEmail)) {
      next.email = '올바른 이메일 형식으로 입력해 주세요.'
    }
    if (!password) next.password = '비밀번호를 입력해 주세요.'
    return next
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (isSubmitting) return

    const nextErrors = validate()
    setErrors(nextErrors)
    setStatusMessage('')

    if (nextErrors.email) {
      emailRef.current?.focus()
      return
    }
    if (nextErrors.password) {
      passwordRef.current?.focus()
      return
    }

    const controller = new AbortController()
    requestRef.current?.abort()
    requestRef.current = controller
    setIsSubmitting(true)

    try {
      await login({ email: normalizeEmail(email), password }, controller.signal)
      clearLoginEmail()
    } catch (error) {
      if (controller.signal.aborted) return

      if (error instanceof ApiClientError && error.code === 'INVALID_CREDENTIALS') {
        setStatusMessage('이메일 또는 비밀번호를 확인해 주세요.')
        passwordRef.current?.focus()
      } else if (error instanceof ApiClientError) {
        setStatusMessage(error.message)
      } else {
        setStatusMessage('로그인 요청을 처리하지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null
        setIsSubmitting(false)
      }
    }
  }

  return (
    <main className={pageStyles.page}>
      <div className={pageStyles.ambient} aria-hidden="true" />
      <header className={pageStyles.header}>
        <Link className={pageStyles.wordmark} to="/" aria-label="Thorp 처음 화면">
          Thorp<span>.</span>
        </Link>
        <Link className={pageStyles.loginLink} to="/signup">
          회원가입
        </Link>
      </header>

      <section className={pageStyles.layout} aria-labelledby="login-title">
        <aside className={pageStyles.intro}>
          <span className={pageStyles.eyebrow}>WELCOME BACK</span>
          <h1 id="login-title" ref={headingRef} tabIndex={-1}>
            다시 전략을
            <br />
            이어가세요.
          </h1>
          <p>로그인하면 나의 전략 기록과 진행 중인 분석을 이어볼 수 있습니다.</p>
          <Link className={pageStyles.backLink} to="/">
            <ArrowLeft size={15} aria-hidden="true" />
            처음 화면으로
          </Link>
        </aside>

        <div className={`${pageStyles.formPanel} ${styles.loginPanel}`}>
          <div className={pageStyles.panelHeading}>
            <span>02</span>
            <div>
              <h2>로그인</h2>
              <p>가입한 이메일과 비밀번호를 입력해 주세요.</p>
            </div>
          </div>

          <form className={`${pageStyles.form} ${styles.loginForm}`} onSubmit={handleSubmit} noValidate>
            <AuthField
              ref={emailRef}
              label="이메일"
              type="email"
              name="email"
              autoComplete="email"
              placeholder="name@example.com"
              value={email}
              error={errors.email}
              disabled={isSubmitting}
              onChange={(event) => {
                setEmail(event.target.value)
                setErrors((current) => ({ ...current, email: undefined }))
              }}
            />
            <AuthField
              ref={passwordRef}
              label="비밀번호"
              type="password"
              name="password"
              autoComplete="current-password"
              placeholder="비밀번호 입력"
              value={password}
              error={errors.password}
              disabled={isSubmitting}
              onChange={(event) => {
                setPassword(event.target.value)
                setErrors((current) => ({ ...current, password: undefined }))
              }}
            />
            <AuthStatus message={statusMessage} tone="error" />
            <button
              type="submit"
              className={`${formStyles.submitButton} ${styles.submitButton}`}
              disabled={isSubmitting}
            >
              <LogIn size={18} aria-hidden="true" />
              {isSubmitting ? '로그인 중…' : '로그인'}
            </button>
          </form>

          <p className={pageStyles.loginPrompt}>
            계정이 없나요? <Link to="/signup">회원가입</Link>
          </p>
        </div>
      </section>
    </main>
  )
}
