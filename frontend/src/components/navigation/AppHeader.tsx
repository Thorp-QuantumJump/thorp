import {
  ChartNoAxesCombined,
  FolderClock,
  Home,
  LogIn,
  LogOut,
  Menu,
  Play,
  RefreshCw,
  UserRound,
  X,
} from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '@/auth/auth-context'
import styles from './AppHeader.module.css'

const navigationItems = [
  {
    label: '홈',
    description: '시장 흐름과 과거 유사 시장을 살펴봅니다.',
    path: '/home',
    icon: Home,
  },
  {
    label: '대시보드',
    description: '핵심 지표와 추천 전략을 빠르게 확인합니다.',
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

/** 모든 서비스 화면에서 주요 기능과 로그인 화면으로 이동할 수 있는 공통 탐색을 제공한다. */
export function AppHeader() {
  const location = useLocation()
  const { status: authStatus, user, logout, recheck } = useAuth()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [isScrolled, setIsScrolled] = useState(false)
  const [pendingAuthAction, setPendingAuthAction] = useState<'logout' | 'recheck' | null>(null)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const firstMenuLinkRef = useRef<HTMLAnchorElement>(null)
  const drawerRef = useRef<HTMLElement>(null)
  const recoveryButtonRef = useRef<HTMLButtonElement>(null)
  const loginLinkRef = useRef<HTMLAnchorElement>(null)
  const previousAuthStatusRef = useRef(authStatus)

  useEffect(() => {
    const previousStatus = previousAuthStatusRef.current
    previousAuthStatusRef.current = authStatus

    if (previousStatus === 'authenticated' && authStatus === 'logoutUnconfirmed') {
      recoveryButtonRef.current?.focus()
    } else if (previousStatus === 'logoutUnconfirmed' && authStatus === 'anonymous') {
      loginLinkRef.current?.focus()
    }
  }, [authStatus])

  // 닫기 동작 뒤 햄버거 버튼으로 초점을 되돌려 키보드 흐름을 유지한다.
  const closeMenu = useCallback(() => {
    setIsMenuOpen(false)
    menuButtonRef.current?.focus()
  }, [])

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
    firstMenuLinkRef.current?.focus()

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return

      event.preventDefault()
      closeMenu()
    }

    document.addEventListener('keydown', handleEscape)

    return () => {
      document.removeEventListener('keydown', handleEscape)
      document.body.style.overflow = previousOverflow
    }
  }, [closeMenu, isMenuOpen])

  // Tab 순환을 처리해 열린 메뉴 안에서 키보드 탐색이 이어지게 한다.
  const handleDrawerKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
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

  const handleLogout = async () => {
    if (pendingAuthAction) return
    setPendingAuthAction('logout')
    try {
      await logout()
    } catch {
      // AuthProvider가 보호 정보를 숨긴 채 복구 가능한 미확인 상태를 유지한다.
    } finally {
      setPendingAuthAction(null)
    }
  }

  const handleRecheck = async () => {
    if (pendingAuthAction) return
    setPendingAuthAction('recheck')
    await recheck()
    setPendingAuthAction(null)
  }

  const desktopAuthControl = (() => {
    if (authStatus === 'checking') {
      return <span className={styles.authState} role="status">확인 중</span>
    }
    if (authStatus === 'authenticated' && user) {
      return (
        <div className={styles.authActions}>
          <Link className={styles.authLink} to="/records" aria-label={`${user.nickname} 로그인됨`}>
            <UserRound size={16} aria-hidden="true" />
            {user.nickname}
          </Link>
          <button
            type="button"
            className={styles.authButton}
            disabled={pendingAuthAction !== null}
            onClick={() => void handleLogout()}
          >
            <LogOut size={16} aria-hidden="true" />
            로그아웃
          </button>
        </div>
      )
    }
    if (authStatus === 'logoutUnconfirmed') {
      return (
        <div className={styles.authRecovery}>
          <span className={styles.authNotice} role="alert">로그아웃 상태를 확인하지 못했습니다.</span>
          <button
            ref={recoveryButtonRef}
            type="button"
            className={styles.authButton}
            disabled={pendingAuthAction !== null}
            onClick={() => void handleLogout()}
          >
            <LogOut size={16} aria-hidden="true" />
            로그아웃 다시 시도
          </button>
          <button
            type="button"
            className={styles.authButton}
            disabled={pendingAuthAction !== null}
            onClick={() => void handleRecheck()}
          >
            <RefreshCw size={16} aria-hidden="true" />
            로그인 상태 다시 확인
          </button>
        </div>
      )
    }
    if (authStatus === 'error') {
      return (
        <div className={styles.authRecovery}>
          <span className={styles.authNotice} role="status">로그인 상태를 확인하지 못했습니다.</span>
          <button
            type="button"
            className={styles.authButton}
            disabled={pendingAuthAction !== null}
            onClick={() => void handleRecheck()}
          >
            <RefreshCw size={16} aria-hidden="true" />
            로그인 상태 다시 확인
          </button>
        </div>
      )
    }
    if (authStatus === 'expired') {
      return (
        <div className={styles.authRecovery}>
          <span className={styles.authNotice} role="status">로그인이 만료되었습니다.</span>
          <Link className={styles.authLink} to="/login">
            <LogIn size={16} aria-hidden="true" />
            다시 로그인
          </Link>
        </div>
      )
    }
    return (
      <Link ref={loginLinkRef} className={styles.authLink} to="/login">
        <LogIn size={16} aria-hidden="true" />
        로그인
      </Link>
    )
  })()

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

          <div className={styles.accountLinks}>
            <Link
              className={styles.recordsLink}
              to="/records"
              aria-current={location.pathname.startsWith('/records') ? 'page' : undefined}
            >
              <FolderClock size={16} aria-hidden="true" />
              내 기록
            </Link>
            {desktopAuthControl}
          </div>
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

              <Link
                className={`${styles.drawerLink} ${styles.drawerRecords}`}
                to="/records"
                aria-current={location.pathname.startsWith('/records') ? 'page' : undefined}
                onClick={() => setIsMenuOpen(false)}
              >
                <span className={styles.drawerIcon}>
                  <FolderClock size={20} aria-hidden="true" />
                </span>
                <span>
                  <strong>내 기록</strong>
                  <small>저장한 분석과 전략 검증 기록을 확인합니다.</small>
                </span>
              </Link>

              {authStatus === 'authenticated' && user ? (
                <div className={styles.drawerAuthActions}>
                  <Link className={styles.drawerAuth} to="/records" onClick={() => setIsMenuOpen(false)}>
                    <UserRound size={18} aria-hidden="true" />
                    {user.nickname}
                  </Link>
                  <button
                    type="button"
                    className={styles.drawerAuth}
                    disabled={pendingAuthAction !== null}
                    onClick={() => void handleLogout()}
                  >
                    <LogOut size={18} aria-hidden="true" />
                    로그아웃
                  </button>
                </div>
              ) : authStatus === 'checking' ? (
                <span className={styles.drawerAuth} role="status">
                  확인 중
                </span>
              ) : authStatus === 'logoutUnconfirmed' ? (
                <div className={styles.drawerAuthActions}>
                  <span className={styles.drawerNotice} role="alert">로그아웃 상태를 확인하지 못했습니다.</span>
                  <button type="button" className={styles.drawerAuth} onClick={() => void handleLogout()}>
                    <LogOut size={18} aria-hidden="true" /> 로그아웃 다시 시도
                  </button>
                  <button type="button" className={styles.drawerAuth} onClick={() => void handleRecheck()}>
                    <RefreshCw size={18} aria-hidden="true" /> 로그인 상태 다시 확인
                  </button>
                </div>
              ) : (
                <Link className={styles.drawerAuth} to="/login" onClick={() => setIsMenuOpen(false)}>
                  <LogIn size={18} aria-hidden="true" />
                  {authStatus === 'expired' ? '다시 로그인' : '로그인'}
                </Link>
              )}
            </motion.nav>
          </div>
        )}
      </AnimatePresence>
    </>
  )
}
