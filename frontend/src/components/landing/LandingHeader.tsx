import { ArrowUpRight, Menu, X } from 'lucide-react'
import { useState, type MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import styles from '@/pages/LandingPage.module.css'

const navigationItems = [
  { href: '#market-analysis', label: '시장 분석' },
  { href: '#backtest-service', label: '백테스트' },
  { href: '#algorithms', label: '알고리즘' },
]

/** 랜딩 안에서만 사용하는 섹션 탐색 헤더다. */
export function LandingHeader() {
  const [isMenuOpen, setIsMenuOpen] = useState(false)

  const moveToSection = (event: MouseEvent<HTMLAnchorElement>, href: string) => {
    const target = document.querySelector(href)
    if (!target) return

    event.preventDefault()
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    target.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
    setIsMenuOpen(false)
  }

  return (
    <header className={styles.landingHeader}>
      <Link className={styles.wordmark} to="/" aria-label="Thorp 랜딩 홈">
        Thorp<span>.</span>
      </Link>

      <nav className={styles.desktopNavigation} aria-label="랜딩 주요 메뉴">
        {navigationItems.map((item) => (
          <a key={item.href} href={item.href} onClick={(event) => moveToSection(event, item.href)}>
            {item.label}
          </a>
        ))}
      </nav>

      <Link className={styles.headerCta} to="/dashboard">
        서비스 시작
        <ArrowUpRight aria-hidden="true" size={16} strokeWidth={1.8} />
      </Link>

      <button
        type="button"
        className={styles.menuButton}
        aria-label={isMenuOpen ? '메뉴 닫기' : '메뉴 열기'}
        aria-expanded={isMenuOpen}
        aria-controls="landing-mobile-navigation"
        onClick={() => setIsMenuOpen((current) => !current)}
      >
        {isMenuOpen ? <X aria-hidden="true" size={20} /> : <Menu aria-hidden="true" size={20} />}
      </button>

      {isMenuOpen ? (
        <nav
          id="landing-mobile-navigation"
          className={styles.mobileNavigation}
          aria-label="랜딩 모바일 메뉴"
        >
          {navigationItems.map((item) => (
            <a key={item.href} href={item.href} onClick={(event) => moveToSection(event, item.href)}>
              {item.label}
            </a>
          ))}
          <Link to="/dashboard" onClick={() => setIsMenuOpen(false)}>
            서비스 시작
          </Link>
        </nav>
      ) : null}
    </header>
  )
}
