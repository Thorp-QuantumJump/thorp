import {
  ChartNoAxesCombined,
  Home,
  Menu,
  Play,
  X,
} from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import styles from './AppHeader.module.css'

const navigationItems = [
  {
    label: '시장 대시보드',
    description: '현재 한국 시장을 쉽게 확인합니다.',
    path: '/dashboard',
    icon: ChartNoAxesCombined,
  },
  {
    label: '백테스트',
    description: '여러 투자 전략의 성과를 비교합니다.',
    path: '/backtest',
    icon: Play,
  },
]

/** 모든 서비스 화면에서 홈·대시보드·백테스트로 이동할 수 있는 공통 탐색을 제공한다. */
export function AppHeader() {
  const location = useLocation()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [isScrolled, setIsScrolled] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const firstMenuLinkRef = useRef<HTMLAnchorElement>(null)
  const drawerRef = useRef<HTMLElement>(null)

  // 페이지 상단을 벗어나면 투명 탐색을 플로팅 바 형태로 전환한다.
  useEffect(() => {
    let frameId = 0

    const updateHeaderState = () => {
      window.cancelAnimationFrame(frameId)
      frameId = window.requestAnimationFrame(() => setIsScrolled(window.scrollY > 56))
    }

    updateHeaderState()
    window.addEventListener('scroll', updateHeaderState, { passive: true })

    return () => {
      window.removeEventListener('scroll', updateHeaderState)
      window.cancelAnimationFrame(frameId)
    }
  }, [])

  // 메뉴가 열리면 배경 스크롤을 막고 첫 번째 탐색 항목으로 초점을 이동한다.
  useEffect(() => {
    if (!isMenuOpen) return

    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusFrame = window.requestAnimationFrame(() => firstMenuLinkRef.current?.focus())

    return () => {
      window.cancelAnimationFrame(focusFrame)
      document.body.style.overflow = previousOverflow
    }
  }, [isMenuOpen])

  // 닫기 동작 뒤 햄버거 버튼으로 초점을 되돌려 키보드 흐름을 유지한다.
  const closeMenu = () => {
    setIsMenuOpen(false)
    window.requestAnimationFrame(() => menuButtonRef.current?.focus())
  }

  // Escape와 Tab 순환을 처리해 열린 메뉴 안에서 키보드 탐색이 이어지게 한다.
  const handleDrawerKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeMenu()
      return
    }

    if (event.key !== 'Tab' || !drawerRef.current) return

    const focusableElements = Array.from(
      drawerRef.current.querySelectorAll<HTMLElement>('a[href], button:not(:disabled)'),
    )
    const firstElement = focusableElements[0]
    const lastElement = focusableElements.at(-1)

    if (event.shiftKey && document.activeElement === firstElement) {
      event.preventDefault()
      lastElement?.focus()
    } else if (!event.shiftKey && document.activeElement === lastElement) {
      event.preventDefault()
      firstElement?.focus()
    }
  }

  return (
    <>
      <header className={styles.header} data-scrolled={isScrolled}>
        <div className={styles.inner} data-scrolled={isScrolled}>
          <div className={styles.brandGroup}>
            <button
              ref={menuButtonRef}
              type="button"
              className={styles.menuButton}
              aria-label="전체 메뉴 열기"
              aria-controls="service-navigation-drawer"
              aria-expanded={isMenuOpen}
              onClick={() => setIsMenuOpen(true)}
            >
              <Menu size={20} aria-hidden="true" />
            </button>
            <Link className={styles.brand} to="/" aria-label="Thorp 처음 화면">
              Thorp<span>.</span>
            </Link>
          </div>

          <nav className={styles.desktopNav} aria-label="주요 화면">
            {navigationItems.map((item) => {
              const Icon = item.icon
              const isActive = location.pathname === item.path

              return (
                <Link
                  key={item.path}
                  to={item.path}
                  aria-current={isActive ? 'page' : undefined}
                >
                  <Icon size={15} aria-hidden="true" />
                  {item.label}
                </Link>
              )
            })}
          </nav>

          <Link className={styles.homeLink} to="/">
            <Home size={16} aria-hidden="true" />
            처음으로
          </Link>
        </div>
      </header>

      <AnimatePresence>
        {isMenuOpen && (
          <div className={styles.menuLayer}>
            <motion.button
              type="button"
              className={styles.backdrop}
              aria-label="전체 메뉴 닫기"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={closeMenu}
            />
            <motion.nav
              ref={drawerRef}
              id="service-navigation-drawer"
              className={styles.drawer}
              role="dialog"
              aria-modal="true"
              aria-labelledby="service-navigation-title"
              initial={{ x: '-100%' }}
              animate={{ x: 0 }}
              exit={{ x: '-100%' }}
              transition={{ duration: 0.24, ease: [0.22, 1, 0.36, 1] }}
              onKeyDown={handleDrawerKeyDown}
            >
              <div className={styles.drawerHeader}>
                <div>
                  <span>THORP</span>
                  <strong id="service-navigation-title">메뉴</strong>
                </div>
                <button type="button" aria-label="전체 메뉴 닫기" onClick={closeMenu}>
                  <X size={21} aria-hidden="true" />
                </button>
              </div>

              <div className={styles.drawerLinks}>
                {navigationItems.map((item, index) => {
                  const Icon = item.icon
                  const isActive = location.pathname === item.path

                  return (
                    <Link
                      ref={index === 0 ? firstMenuLinkRef : undefined}
                      key={item.path}
                      className={styles.drawerLink}
                      to={item.path}
                      aria-current={isActive ? 'page' : undefined}
                      onClick={() => setIsMenuOpen(false)}
                    >
                      <span className={styles.drawerIcon}>
                        <Icon size={20} aria-hidden="true" />
                      </span>
                      <span>
                        <strong>{item.label}</strong>
                        <small>{item.description}</small>
                      </span>
                    </Link>
                  )
                })}
              </div>

              <Link className={styles.drawerHome} to="/" onClick={() => setIsMenuOpen(false)}>
                <Home size={18} aria-hidden="true" />
                처음 화면으로
              </Link>
            </motion.nav>
          </div>
        )}
      </AnimatePresence>
    </>
  )
}
