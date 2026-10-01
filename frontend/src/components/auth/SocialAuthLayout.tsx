import { ArrowLeft } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import pageStyles from '@/pages/SignupPage.module.css'

type SocialAuthLayoutProps = {
  eyebrow: string
  /** 화면 제목. 화면이 바뀌면 초점이 여기로 온다(FR-016). */
  title: string
  description: ReactNode
  panelTitle: string
  panelDescription?: ReactNode
  children: ReactNode
}

/**
 * 소셜 로그인 뒤 화면들(추가 정보·이메일 확인·계정 연결·거절 안내)의 공통 틀.
 *
 * <p>로그인·회원가입 화면과 같은 짜임을 쓴다. 제공자를 다녀온 사용자가 다른 서비스에 온 것처럼
 * 느끼지 않게 한다.
 */
export function SocialAuthLayout({
  eyebrow,
  title,
  description,
  panelTitle,
  panelDescription,
  children,
}: SocialAuthLayoutProps) {
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  return (
    <main className={pageStyles.page}>
      <div className={pageStyles.ambient} aria-hidden="true" />
      <header className={pageStyles.header}>
        <Link className={pageStyles.wordmark} to="/" aria-label="Thorp 처음 화면">
          Thorp<span>.</span>
        </Link>
        <Link className={pageStyles.loginLink} to="/login">
          로그인
        </Link>
      </header>

      <section className={pageStyles.layout} aria-labelledby="social-auth-title">
        <aside className={pageStyles.intro}>
          <span className={pageStyles.eyebrow}>{eyebrow}</span>
          <h1 id="social-auth-title" ref={headingRef} tabIndex={-1}>
            {title}
          </h1>
          <p>{description}</p>
          <Link className={pageStyles.backLink} to="/login">
            <ArrowLeft size={15} aria-hidden="true" />
            로그인 화면으로
          </Link>
        </aside>

        <div className={pageStyles.formPanel}>
          <div className={pageStyles.panelHeading}>
            <div>
              <h2>{panelTitle}</h2>
              {panelDescription ? <p>{panelDescription}</p> : null}
            </div>
          </div>
          {children}
        </div>
      </section>
    </main>
  )
}
