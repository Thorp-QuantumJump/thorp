import { AnimatePresence, MotionConfig, motion } from 'motion/react'
import { lazy, Suspense, useLayoutEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthEntryGuard } from '@/auth/AuthEntryGuard'
import { LandingPage } from '@/pages/LandingPage'

// 초기 진입 번들을 줄이기 위해 백테스트 화면은 접근 시점에 불러온다.
const BacktestPage = lazy(() =>
  import('@/pages/BacktestPage').then((module) => ({
    default: module.BacktestPage,
  })),
)

// 상세 홈과 요약 대시보드는 각 경로에 접근할 때 불러온다.
const HomePage = lazy(() =>
  import('@/pages/HomePage').then((module) => ({
    default: module.HomePage,
  })),
)

const DashboardPage = lazy(() =>
  import('@/pages/DashboardPage').then((module) => ({
    default: module.DashboardPage,
  })),
)

const SignupPage = lazy(() =>
  import('@/pages/SignupPage').then((module) => ({
    default: module.SignupPage,
  })),
)

const LoginPage = lazy(() =>
  import('@/pages/LoginPage').then((module) => ({
    default: module.LoginPage,
  })),
)

/** 현재 경로에 맞는 페이지를 전환 애니메이션과 함께 렌더링한다. */
function AnimatedRoutes() {
  const location = useLocation()

  // 페이지 경로가 바뀌면 이전 화면의 스크롤 위치를 이어받지 않도록 최상단으로 이동한다.
  useLayoutEffect(() => {
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
  }, [location.pathname])

  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.div
        key={location.pathname}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -6 }}
        transition={{ duration: 0.24, ease: [0.22, 1, 0.36, 1] }}
      >
        <Suspense fallback={<div role="status" aria-label="페이지를 불러오는 중" />}>
          <Routes location={location}>
            <Route path="/" element={<LandingPage />} />
            <Route path="/home" element={<HomePage />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/backtest" element={<BacktestPage />} />
            <Route
              path="/signup"
              element={
                <AuthEntryGuard>
                  <SignupPage />
                </AuthEntryGuard>
              }
            />
            <Route
              path="/login"
              element={
                <AuthEntryGuard>
                  <LoginPage />
                </AuthEntryGuard>
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </motion.div>
    </AnimatePresence>
  )
}

/** 전역 모션 접근성 설정을 적용하고 애플리케이션 라우트를 구성한다. */
function App() {
  return (
    <MotionConfig reducedMotion="user">
      <AnimatedRoutes />
    </MotionConfig>
  )
}

export default App
