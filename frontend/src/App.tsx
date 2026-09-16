import { AnimatePresence, MotionConfig, motion } from 'motion/react'
import { lazy, Suspense, useLayoutEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthEntryGuard } from '@/auth/AuthEntryGuard'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { LandingPage } from '@/pages/LandingPage'

// 초기 진입 번들을 줄이기 위해 백테스트 화면은 접근 시점에 불러온다.
const BacktestPage = lazy(() =>
  import('@/pages/BacktestPage').then((module) => ({
    default: module.BacktestPage,
  })),
)

// 상세 홈과 요약 대시보드는 각 경로에 접근할 때 불러온다.
// 설정 조합 자동 비교(spec 007)도 전략 화면에서 넘어올 때 불러온다.
const ParamSearchPage = lazy(() =>
  import('@/pages/ParamSearchPage').then((module) => ({
    default: module.ParamSearchPage,
  })),
)

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

const MyRecordsEntryPage = lazy(() =>
  import('@/pages/MyRecordsEntryPage').then((module) => ({
    default: module.MyRecordsEntryPage,
  })),
)

// 아직 만들지 않은 메뉴를 정상 화면으로 받는다.
const NotReadyPage = lazy(() =>
  import('@/pages/NotReadyPage').then((module) => ({
    default: module.NotReadyPage,
  })),
)

// 전략 진단의 첫 단계다. 섹터 목록을 받아 보이는 화면이며 접근 시점에 불러온다.
const SectorSelectionPage = lazy(() =>
  import('@/pages/SectorSelectionPage').then((module) => ({
    default: module.SectorSelectionPage,
  })),
)

// 전략 진단의 두 번째 단계다(spec 003). 흐름은 002 섹터·비중(`/diagnosis/sectors`) → 003 전략
// 선택(`/strategy`) → 004 분석 / 007 설정 조합 자동 비교(`/param-search`)이며, 앞 단계의 선택은
// 같은 탭의 `sessionStorage`로 이어받는다.
const StrategySettingsPage = lazy(() =>
  import('@/pages/StrategySettingsPage').then((module) => ({
    default: module.StrategySettingsPage,
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
            <Route path="/param-search" element={<ParamSearchPage />} />
            {/* 배우기는 아직 화면이 없다. 메뉴에서 빼지 않고 준비 중 화면으로 받는다. */}
            <Route path="/learn" element={<NotReadyPage />} />
            {/* 첫 화면의 '비중 비교하기'가 닿는 자리다. 준비 중 화면을 대신한다. */}
            <Route path="/diagnosis/sectors" element={<SectorSelectionPage />} />
            {/* 섹터·비중을 마친 뒤 전략을 고르는 자리다. 앞 단계를 마치지 않았으면 되돌린다. */}
            <Route path="/strategy" element={<StrategySettingsPage />} />
            <Route
              path="/records/*"
              element={
                <ProtectedRoute>
                  <MyRecordsEntryPage />
                </ProtectedRoute>
              }
            />
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
