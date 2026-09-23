import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { AppHeader } from '@/components/navigation/AppHeader'
import { authenticatedUser } from '@/test/authFixtures'
import { useAuth } from './auth-context'
import { AuthProvider } from './AuthProvider'

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
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function Probe() {
  const auth = useAuth()
  return (
    <div>
      <output data-testid="snapshot">{auth.status}:{auth.user?.nickname ?? 'none'}</output>
      <button type="button" onClick={() => void auth.logout().catch(() => undefined)}>로그아웃 실행</button>
      <button type="button" onClick={() => void auth.recheck()}>상태 확인</button>
    </div>
  )
}

describe('logout flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
  })

  it('로그아웃 시작 즉시 사용자를 지우고 204 확인 뒤 anonymous가 된다', async () => {
    const pendingLogout = deferred<void>()
    apiMocks.logout.mockReturnValue(pendingLogout.promise)
    const user = userEvent.setup()

    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:쏘프사용자')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '로그아웃 실행' }))
    expect(screen.getByTestId('snapshot').textContent).toBe('logoutUnconfirmed:none')

    await act(async () => pendingLogout.resolve())
    expect(screen.getByTestId('snapshot').textContent).toBe('anonymous:none')
  })

  it('응답을 확인하지 못하면 보호 정보를 숨긴 상태를 유지하고 재확인할 수 있다', async () => {
    apiMocks.logout.mockRejectedValue(new ApiClientError('network', '연결 실패'))
    const user = userEvent.setup()

    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:쏘프사용자')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '로그아웃 실행' }))
    expect(await screen.findByText('logoutUnconfirmed:none')).toBeTruthy()

    apiMocks.getCurrentUser.mockRejectedValueOnce(
      new ApiClientError('api', '로그인이 필요합니다.', {
        code: 'AUTHENTICATION_REQUIRED',
      }),
    )
    await user.click(screen.getByRole('button', { name: '상태 확인' }))
    expect(await screen.findByText('anonymous:none')).toBeTruthy()
  })

  it('헤더에서 로그아웃 실패를 알리고 재시도할 수 있다', async () => {
    apiMocks.logout
      .mockRejectedValueOnce(new ApiClientError('network', '연결 실패'))
      .mockResolvedValueOnce(undefined)
    const user = userEvent.setup()

    render(
      <MemoryRouter>
        <AuthProvider><AppHeader /></AuthProvider>
      </MemoryRouter>,
    )
    expect(await screen.findByText('쏘프사용자')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect((await screen.findByRole('alert')).textContent).toContain('로그아웃 상태를 확인하지 못했습니다')
    const retryButton = screen.getByRole('button', { name: '로그아웃 다시 시도' })
    expect(document.activeElement).toBe(retryButton)
    await user.click(retryButton)
    const loginLink = await screen.findByRole('link', { name: '로그인' })
    expect(document.activeElement).toBe(loginLink)
  })
})
