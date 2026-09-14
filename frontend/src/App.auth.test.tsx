import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import App from './App'

vi.mock('@/pages/LandingPage', () => ({ LandingPage: () => <h1>랜딩 화면</h1> }))
vi.mock('@/pages/HomePage', () => ({ HomePage: () => <h1>홈 화면</h1> }))
vi.mock('@/pages/DashboardPage', () => ({ DashboardPage: () => <h1>대시보드 화면</h1> }))
vi.mock('@/pages/BacktestPage', () => ({ BacktestPage: () => <h1>백테스트 화면</h1> }))
vi.mock('@/pages/LoginPage', () => ({ LoginPage: () => <h1>로그인 화면</h1> }))
vi.mock('@/pages/SignupPage', () => ({ SignupPage: () => <h1>회원가입 화면</h1> }))
vi.mock('@/pages/MyRecordsEntryPage', () => ({
  MyRecordsEntryPage: () => <h1>내 기록 화면</h1>,
}))

const anonymousAuth: AuthContextValue = {
  status: 'anonymous',
  user: null,
  login: vi.fn(),
  recheck: vi.fn(),
}

const authenticatedAuth: AuthContextValue = {
  ...anonymousAuth,
  status: 'authenticated',
  user: authenticatedUser,
}

function renderApp(path: string, auth = anonymousAuth) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={auth}>
        <App />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('App authentication routes', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it.each([
    ['/home', '홈 화면'],
    ['/dashboard', '대시보드 화면'],
    ['/backtest', '백테스트 화면'],
  ])('비로그인 상태에서도 공개 경로 %s를 이용할 수 있다', async (path, heading) => {
    renderApp(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeTruthy()
  })

  it('내 기록 경로만 로그인 화면으로 연결하고 원래 하위 위치를 저장한다', async () => {
    renderApp('/records/run-17?tab=trades#cost')

    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe(
      '/records/run-17?tab=trades#cost',
    )
  })

  it('로그인 사용자는 내 기록 진입 화면을 바로 볼 수 있다', async () => {
    renderApp('/records', authenticatedAuth)

    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
  })
})
