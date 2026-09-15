import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { authenticatedUser } from '@/test/authFixtures'
import { useAuth } from './auth-context'
import { AuthProvider } from './AuthProvider'
import { ProtectedRoute } from './ProtectedRoute'

const apiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...apiMocks }
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function Probe() {
  const auth = useAuth()
  return (
    <>
      <h1>공개 시장 화면</h1>
      <output data-testid="snapshot">{auth.status}:{auth.user?.email ?? 'none'}</output>
    </>
  )
}

describe('authentication revalidation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
  })

  it('숨겨졌던 탭이 다시 보이면 사용자를 지운 확인 중 상태에서 재조회한다', async () => {
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()

    const pending = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser.mockReturnValueOnce(pending.promise)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    act(() => document.dispatchEvent(new Event('visibilitychange')))

    expect(screen.getByTestId('snapshot').textContent).toBe('checking:none')
    expect(screen.getByRole('heading', { name: '공개 시장 화면' })).toBeTruthy()
    expect(apiMocks.getCurrentUser).toHaveBeenCalledTimes(2)
    await act(async () => pending.resolve(authenticatedUser))
    expect(screen.getByTestId('snapshot').textContent).toBe('authenticated:user@example.com')
  })

  it('bfcache에서 복원되면 이전 보호 사용자를 먼저 지우고 재조회한다', async () => {
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()

    const pending = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser.mockReturnValueOnce(pending.promise)
    const pageShow = new Event('pageshow')
    Object.defineProperty(pageShow, 'persisted', { value: true })
    act(() => window.dispatchEvent(pageShow))

    expect(screen.getByTestId('snapshot').textContent).toBe('checking:none')
    await act(async () => pending.resolve(authenticatedUser))
  })

  it('보호 경로에 들어갈 때 세션을 다시 확인할 때까지 이전 보호 내용을 숨긴다', async () => {
    const protectedEntry = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser
      .mockResolvedValueOnce(authenticatedUser)
      .mockReturnValueOnce(protectedEntry.promise)

    render(
      <MemoryRouter initialEntries={['/records/analysis-42']}>
        <AuthProvider>
          <ProtectedRoute><h1>보호된 분석 기록</h1></ProtectedRoute>
        </AuthProvider>
      </MemoryRouter>,
    )

    await waitFor(() => expect(apiMocks.getCurrentUser).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('heading', { name: '보호된 분석 기록' })).toBeNull()
    expect(screen.getByRole('status').textContent).toContain('로그인 상태를 확인')

    await act(async () => protectedEntry.resolve(authenticatedUser))
    expect(await screen.findByRole('heading', { name: '보호된 분석 기록' })).toBeTruthy()
  })

  it.each([
    ['탭 재활성화', () => document.dispatchEvent(new Event('visibilitychange'))],
    ['bfcache 복원', () => {
      const pageShow = new Event('pageshow')
      Object.defineProperty(pageShow, 'persisted', { value: true })
      window.dispatchEvent(pageShow)
    }],
  ])('%s 재확인에서 401을 받으면 expired로 전환한다', async (_label, restorePage) => {
    apiMocks.getCurrentUser
      .mockResolvedValueOnce(authenticatedUser)
      .mockRejectedValueOnce(
        new ApiClientError('api', '로그인이 필요합니다.', {
          code: 'AUTHENTICATION_REQUIRED',
        }),
      )

    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()

    act(() => restorePage())

    expect(await screen.findByText('expired:none')).toBeTruthy()
  })
})
