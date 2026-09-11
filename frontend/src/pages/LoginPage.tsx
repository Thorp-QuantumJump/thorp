import { ArrowLeft } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import formStyles from '@/components/auth/AuthForm.module.css'
import styles from './SignupPage.module.css'

function readSignupEmail(state: unknown): string {
  if (typeof state !== 'object' || state === null || !('signupEmail' in state)) return ''
  return typeof state.signupEmail === 'string' ? state.signupEmail : ''
}

/** 로그인 기능 작업 전에도 가입 완료 이메일을 안전하게 인계하는 route shell이다. */
export function LoginPage() {
  const location = useLocation()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [email, setEmail] = useState(() => readSignupEmail(location.state))
  const [password, setPassword] = useState('')

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
  }

  return (
    <main className={styles.page}>
      <div className={styles.ambient} aria-hidden="true" />
      <header className={styles.header}>
        <Link className={styles.wordmark} to="/" aria-label="Thorp 처음 화면">
          Thorp<span>.</span>
        </Link>
        <Link className={styles.loginLink} to="/signup">
          회원가입
        </Link>
      </header>

      <section className={styles.layout} aria-labelledby="login-title">
        <aside className={styles.intro}>
          <span className={styles.eyebrow}>WELCOME BACK</span>
          <h1 id="login-title" ref={headingRef} tabIndex={-1}>
            다시 전략을
            <br />
            이어가세요.
          </h1>
          <p>로그인과 인증 상태 복원 기능은 다음 FE 작업 묶음에서 연결됩니다.</p>
          <Link className={styles.backLink} to="/">
            <ArrowLeft size={15} aria-hidden="true" />
            처음 화면으로
          </Link>
        </aside>

        <div className={styles.formPanel}>
          <div className={styles.panelHeading}>
            <span>02</span>
            <div>
              <h2>로그인</h2>
              <p>가입 완료 시 이메일만 안전하게 가져옵니다.</p>
            </div>
          </div>

          <form className={styles.form} onSubmit={handleSubmit}>
            <AuthField
              label="이메일"
              type="email"
              name="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <AuthField
              label="비밀번호"
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <AuthStatus
              message="로그인 요청은 로그인·상태 복원 작업에서 연결됩니다."
              tone="info"
            />
            <button type="submit" className={formStyles.submitButton} disabled>
              로그인 준비 중
            </button>
          </form>

          <p className={styles.loginPrompt}>
            계정이 없나요? <Link to="/signup">회원가입</Link>
          </p>
        </div>
      </section>
    </main>
  )
}
