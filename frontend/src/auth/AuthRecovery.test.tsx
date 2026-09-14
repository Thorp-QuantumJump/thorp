import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { login } from '@/api/auth'
import { ApiClientError, apiClient, resetCsrfToken } from '@/api/client'
import { authenticatedUser, authenticatedUserSuccess, authError } from '@/test/authFixtures'
import { useAuth } from './auth-context'
import { AuthProvider } from './AuthProvider'

const apiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

const originalAdapter = apiClient.defaults.adapter

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, getCurrentUser: apiMocks.getCurrentUser, logout: apiMocks.logout }
})

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return {
    data,
    status,
    statusText: status >= 400 ? 'Error' : 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

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
    <div>
      <output data-testid="snapshot">{auth.status}:{auth.user?.email ?? 'none'}</output>
      <button type="button" onClick={() => void auth.recheck()}>재확인</button>
    </div>
  )
}

describe('authentication recovery', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    resetCsrfToken()
  })

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter
  })

  it('인증된 요청의 401 신호를 받으면 이전 사용자를 즉시 숨기고 expired가 된다', async () => {
    apiMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()

    const response = axiosResponse(
      authError('AUTHENTICATION_REQUIRED', '로그인이 필요합니다.'),
      401,
    )
    apiClient.defaults.adapter = vi.fn().mockRejectedValueOnce(
      new AxiosError('Unauthorized', 'ERR_BAD_RESPONSE', undefined, undefined, response),
    )

    await apiClient.get('/protected').catch(() => undefined)
    expect(await screen.findByText('expired:none')).toBeTruthy()
  })

  it('인증 상태 재확인에서 401을 받으면 비로그인이 아니라 만료로 구분한다', async () => {
    apiMocks.getCurrentUser
      .mockResolvedValueOnce(authenticatedUser)
      .mockRejectedValueOnce(
        new ApiClientError('api', '로그인이 필요합니다.', {
          code: 'AUTHENTICATION_REQUIRED',
        }),
      )
    const user = userEvent.setup()

    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '재확인' }))

    expect(await screen.findByText('expired:none')).toBeTruthy()
  })

  it('CSRF 403에서는 토큰만 다시 준비하고 원 요청은 사용자가 재시도할 때까지 반복하지 않는다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const csrfError = new AxiosError(
      'Forbidden',
      'ERR_BAD_RESPONSE',
      undefined,
      undefined,
      axiosResponse(authError('CSRF_TOKEN_INVALID', '토큰 만료'), 403),
    )
    const postSpy = vi.spyOn(apiClient, 'post').mockRejectedValueOnce(csrfError)

    await expect(login({ email: 'user@example.com', password: 'password1234' })).rejects.toMatchObject({
      code: 'CSRF_TOKEN_INVALID',
    })
    expect(postSpy).toHaveBeenCalledOnce()
    expect(getSpy).toHaveBeenCalledTimes(2)

    postSpy.mockResolvedValueOnce(axiosResponse(authenticatedUserSuccess))
    await expect(login({ email: 'user@example.com', password: 'password1234' })).resolves.toEqual(authenticatedUser)
    expect(postSpy).toHaveBeenCalledTimes(2)
  })

  it('늦게 도착한 이전 재확인 결과가 최신 사용자 상태를 덮어쓰지 않는다', async () => {
    apiMocks.getCurrentUser.mockResolvedValueOnce(authenticatedUser)
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:user@example.com')).toBeTruthy()

    const first = deferred<typeof authenticatedUser>()
    const second = deferred<typeof authenticatedUser>()
    apiMocks.getCurrentUser.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '재확인' }))
    await user.click(screen.getByRole('button', { name: '재확인' }))

    const newestUser = { ...authenticatedUser, email: 'newest@example.com' }
    await act(async () => second.resolve(newestUser))
    expect(screen.getByTestId('snapshot').textContent).toBe('authenticated:newest@example.com')

    await act(async () => first.resolve(authenticatedUser))
    expect(screen.getByTestId('snapshot').textContent).toBe('authenticated:newest@example.com')
  })
})
