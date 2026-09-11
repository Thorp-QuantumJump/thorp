import { act, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { authenticatedUser } from '@/test/authFixtures'
import { useAuth } from './auth-context'
import { AuthProvider } from './AuthProvider'

const apiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...apiMocks }
})

function Probe() {
  const auth = useAuth()
  return (
    <output data-testid="auth-state">
      {auth.status}:{auth.user?.email ?? 'none'}
    </output>
  )
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('확인 중에는 사용자를 노출하지 않고 200 응답 뒤 인증 상태를 복원한다', async () => {
    const currentUser = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser.mockReturnValue(currentUser.promise)

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )

    expect(screen.getByTestId('auth-state').textContent).toBe('checking:none')
    await act(async () => currentUser.resolve(authenticatedUser))
    expect(screen.getByTestId('auth-state').textContent).toBe(
      'authenticated:user@example.com',
    )
  })

  it('401은 비로그인, 네트워크 실패는 확인 오류로 구분한다', async () => {
    apiMocks.getCurrentUser.mockRejectedValueOnce(
      new ApiClientError('api', '로그인이 필요합니다.', {
        code: 'AUTHENTICATION_REQUIRED',
      }),
    )
    const { unmount } = render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )

    expect(await screen.findByText('anonymous:none')).toBeTruthy()
    unmount()

    apiMocks.getCurrentUser.mockRejectedValueOnce(
      new ApiClientError('network', '서버에 연결할 수 없습니다.'),
    )
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )

    expect(await screen.findByText('error:none')).toBeTruthy()
  })

  it('StrictMode의 재마운트에서도 진행 중인 초기 조회를 한 번만 공유한다', async () => {
    const currentUser = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser.mockReturnValue(currentUser.promise)

    render(
      <StrictMode>
        <AuthProvider>
          <Probe />
        </AuthProvider>
      </StrictMode>,
    )

    expect(apiMocks.getCurrentUser).toHaveBeenCalledOnce()
    await act(async () => currentUser.resolve(authenticatedUser))
    expect(screen.getByTestId('auth-state').textContent).toBe(
      'authenticated:user@example.com',
    )
  })
})
