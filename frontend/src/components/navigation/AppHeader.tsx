import {
  BookOpen,
  ChartNoAxesCombined,
  FolderClock,
  Gauge,
  LogIn,
  LogOut,
  Menu,
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
import { useLoginReturn } from '@/auth/useLoginReturn'
import styles from './AppHeader.module.css'

/**
 * 첫 화면(`LandingHeader`)과 같은 메뉴 넷이다. 목적지도 같다.
 *
 * 첫 화면에서 메뉴를 눌러 들어온 화면의 헤더가 다른 메뉴를 보이면, 사용자는 한 번의 이동으로
 * 서비스의 구성이 통째로 바뀐 것처럼 겪는다.
 *
 * '대시보드'(`/dashboard`)와 '백테스트'(`/backtest`)는 메뉴에서 뺐다. 003·005가 완성되면
 * 대체될 임시 화면이라 언젠가 빠질 자리이기 때문이다. **두 경로 자체는 지우지 않았다.**
 *
 * `covers`는 이 메뉴가 현재 위치로 표시될 경로들이다. **`path` 하나로는 모자란다** — 전략 진단은
 * 섹터·비중(`/diagnosis/sectors`) → 전략 고르기(`/strategy`) → 설정 조합 자동 비교(`/param-search`)
 * → 결과(`/results/…`)로 이어지는 한 흐름인데 주소가 서로 형제라, 두 번째 단계로 넘어가는 순간
 * 표시가 꺼져 사용자는 흐름에서 벗어난 것처럼 겪는다. 흐름이 늘면 여기에 적는다.
 */
const navigationItems = [
  {
    label: '전략 진단',
    description: '섹터와 비중을 골라 과거 성과를 같은 조건에서 비교합니다.',
    path: '/diagnosis/sectors',
    covers: ['/diagnosis', '/strategy', '/param-search', '/results'],
    icon: Gauge,
  },
  {
    label: '시장 흐름',
    description: '시장 흐름과 과거 유사 시장을 살펴봅니다.',
    path: '/market-flow',
    covers: ['/market-flow', '/regime-search'],
    icon: ChartNoAxesCombined,
  },
  {
    label: '배우기',
    description: '투자 개념과 지표 읽는 법을 익힙니다.',
    path: '/learn',
    covers: ['/learn'],
    icon: BookOpen,
  },
  {
    label: '내 기록',
    description: '저장한 분석과 전략 검증 기록을 확인합니다.',
    path: '/records',
    covers: ['/records'],
    icon: FolderClock,
  },
]

/** 하위 경로(`/records/…`)에 있어도 그 메뉴를 현재 위치로 표시한다. */
function isCurrentPath(pathname: string, covers: readonly string[]): boolean {
  return covers.some((path) => pathname === path || pathname.startsWith(`${path}/`))
}

/** 모든 서비스 화면에서 주요 기능과 로그인 화면으로 이동할 수 있는 공통 탐색을 제공한다. */
export function AppHeader() {
  const location = useLocation()
  const { status: authStatus, user, logout, recheck } = useAuth()
  const rememberReturn = useLoginReturn()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
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
          <Link className={styles.authLink} to="/login" onClick={rememberReturn}>
            <LogIn size={16} aria-hidden="true" />
            다시 로그인
          </Link>
        </div>
      )
    }
    return (
      <Link ref={loginLinkRef} className={styles.authLink} to="/login" onClick={rememberReturn}>
        <LogIn size={16} aria-hidden="true" />
        로그인
      </Link>
    )
  })()

  return (
    <>
      <header className={styles.header}>
        <div className={styles.inner}>
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
              const isActive = isCurrentPath(location.pathname, item.covers)

              return (
                <Link
                  key={item.path}
                  to={item.path}
                  aria-current={isActive ? 'page' : undefined}
                >
                  <Icon size={15} aria-hidden="true" />
                  {item.label}
                  {/*
                    활성 표시는 메뉴마다 따로 그리지 않고 같은 `layoutId` 하나를 옮겨 그린다. 그래야
                    표시가 꺼졌다 켜지는 대신 다음 메뉴로 미끄러져, 흐름 안에서 단계가 바뀌었다는
                    것이 한 번의 움직임으로 읽힌다. 모션 축소 설정은 App 의 MotionConfig 가 받는다.
                  */}
                  {isActive ? (
                    <motion.span
                      layoutId="app-header-active"
                      className={styles.navActiveBar}
                      aria-hidden="true"
                      transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
                    />
                  ) : null}
                </Link>
              )
            })}
          </nav>

          {/*
            '내 기록'은 위 메뉴에 있으므로 여기 같은 이름의 링크를 따로 두지 않는다.
            아래 로그인 표시는 목적지가 같아도 하는 일이 다르다. 메뉴 항목이 아니라 지금 누가
            로그인해 있는지를 알리는 자리다.
          */}
          <div className={styles.accountLinks}>{desktopAuthControl}</div>
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
                  const isActive = isCurrentPath(location.pathname, item.covers)

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
                <Link
                  className={styles.drawerAuth}
                  to="/login"
                  onClick={() => {
                    rememberReturn()
                    setIsMenuOpen(false)
                  }}
                >
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
