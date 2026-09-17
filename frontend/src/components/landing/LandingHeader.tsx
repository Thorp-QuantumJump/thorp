import { LogIn, Menu, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import styles from '@/pages/LandingPage.module.css'

/**
 * 첫 화면이 보이는 주요 메뉴다. 아직 화면이 없는 메뉴도 숨기지 않는다.
 * 목적지는 이미 화면이 있으면 그 경로로 잇고, 배우기는 준비 중 화면이 받는다.
 * 최종 목적지 확정은 각 메뉴를 소유한 spec의 몫이다.
 */
const primaryMenuItems = [
  { to: '/diagnosis/sectors', label: '전략 진단' },
  { to: '/market-flow', label: '시장 흐름' },
  { to: '/learn', label: '배우기' },
]

/** 보조 메뉴도 첫 화면에서 함께 보인다. */
const secondaryMenuItem = { to: '/records', label: '내 기록' }

const menuItems = [...primaryMenuItems, secondaryMenuItem]

/** 첫 화면의 메뉴 탐색 헤더다. */
export function LandingHeader() {
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!isMenuOpen) return

    const handleEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      setIsMenuOpen(false)
      window.requestAnimationFrame(() => menuButtonRef.current?.focus())
    }

    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [isMenuOpen])

  return (
    <header className={styles.landingHeader}>
      <Link className={styles.wordmark} to="/" aria-label="Thorp 랜딩 홈">
        Thorp<span>.</span>
      </Link>

      <nav className={styles.desktopNavigation} aria-label="주요 메뉴">
        {primaryMenuItems.map((item) => (
          <Link key={item.to} to={item.to}>
            {item.label}
          </Link>
        ))}
        <Link className={styles.secondaryMenuLink} to={secondaryMenuItem.to}>
          {secondaryMenuItem.label}
        </Link>
      </nav>

      <Link className={styles.headerCta} to="/login">
        로그인
        <LogIn aria-hidden="true" size={16} strokeWidth={1.8} />
      </Link>

      <button
        ref={menuButtonRef}
        type="button"
        className={styles.menuButton}
        aria-label={isMenuOpen ? '메뉴 닫기' : '메뉴 열기'}
        aria-expanded={isMenuOpen}
        aria-controls="landing-mobile-navigation"
        aria-haspopup="true"
        onClick={() => setIsMenuOpen((current) => !current)}
      >
        {isMenuOpen ? <X aria-hidden="true" size={20} /> : <Menu aria-hidden="true" size={20} />}
      </button>

      {isMenuOpen ? (
        <nav
          id="landing-mobile-navigation"
          className={styles.mobileNavigation}
          aria-label="모바일 주요 메뉴"
        >
          {menuItems.map((item) => (
            <Link key={item.to} to={item.to} onClick={() => setIsMenuOpen(false)}>
              {item.label}
            </Link>
          ))}
          <Link to="/login" onClick={() => setIsMenuOpen(false)}>
            로그인
          </Link>
        </nav>
      ) : null}
    </header>
  )
}
