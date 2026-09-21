import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import App from './App'

vi.mock('@/pages/LandingPage', () => ({ LandingPage: () => <h1>랜딩 화면</h1> }))
vi.mock('@/pages/MarketFlowPage', () => ({ MarketFlowPage: () => <h1>시장 흐름 화면</h1> }))
vi.mock('@/pages/DashboardPage', () => ({ DashboardPage: () => <h1>대시보드 화면</h1> }))
vi.mock('@/pages/BacktestPage', () => ({ BacktestPage: () => <h1>백테스트 화면</h1> }))
vi.mock('@/pages/LoginPage', () => ({ LoginPage: () => <h1>로그인 화면</h1> }))
vi.mock('@/pages/SignupPage', () => ({ SignupPage: () => <h1>회원가입 화면</h1> }))
vi.mock('@/pages/MyRecordsPage', () => ({
  MyRecordsPage: () => <h1>내 기록 화면</h1>,
}))

const anonymousAuth: AuthContextValue = {
  status: 'anonymous',
  user: null,
  login: vi.fn(),
  logout: vi.fn(),
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

/** 리다이렉트가 주소를 실제로 바꿨는지, 뒤로 가기가 어디로 가는지 보기 위한 탐침이다. */
function HistoryProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <p data-testid="pathname">{location.pathname}</p>
      <button type="button" onClick={() => navigate(-1)}>
        뒤로 가기
      </button>
    </>
  )
}

function renderAppWithHistory(entries: string[], index: number) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={index}>
      <AuthContext.Provider value={anonymousAuth}>
        <App />
        <HistoryProbe />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('App authentication routes', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it.each([
    ['/market-flow', '시장 흐름 화면'],
    ['/dashboard', '대시보드 화면'],
    ['/backtest', '백테스트 화면'],
  ])('비로그인 상태에서도 공개 경로 %s를 이용할 수 있다', async (path, heading) => {
    renderApp(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeTruthy()
  })

  it('/home은 새 시장 흐름 경로로 연결하고 주소까지 바꾼다', async () => {
    renderAppWithHistory(['/home'], 0)

    expect(await screen.findByRole('heading', { name: '시장 흐름 화면' })).toBeTruthy()
    // 화면만 갈아끼우고 주소가 /home으로 남으면 공유된 링크가 옛 경로를 계속 퍼뜨린다.
    expect(screen.getByTestId('pathname').textContent).toBe('/market-flow')
  })

  it('/home 리다이렉트는 기록을 덮어써서 뒤로 가기가 되돌아오지 않는다', async () => {
    // 랜딩에서 옛 링크를 눌러 들어온 상황이다.
    renderAppWithHistory(['/', '/home'], 1)
    expect(await screen.findByRole('heading', { name: '시장 흐름 화면' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }))

    // replace가 빠지면 뒤로 가기가 /home을 다시 만나 앞으로 튕겨 나온다.
    expect(await screen.findByRole('heading', { name: '랜딩 화면' })).toBeTruthy()
    expect(screen.getByTestId('pathname').textContent).toBe('/')
  })

  it('내 기록 경로만 로그인 화면으로 연결하고 원래 하위 위치를 저장한다', async () => {
    renderApp('/records/run-17?tab=trades#cost')

    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe(
      '/records/run-17?tab=trades#cost',
    )
  })

  it('로그인 사용자는 내 기록 진입 화면을 바로 볼 수 있다', async () => {
    authenticatedAuth.recheck = vi.fn().mockResolvedValue(authenticatedAuth)
    renderApp('/records', authenticatedAuth)

    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
  })
})
