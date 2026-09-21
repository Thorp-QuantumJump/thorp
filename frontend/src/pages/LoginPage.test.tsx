import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { AuthEntryGuard } from '@/auth/AuthEntryGuard'
import { AuthProvider } from '@/auth/AuthProvider'
import { DEFAULT_RETURN_TARGET, saveReturnTarget } from '@/auth/returnTarget'
import { authenticatedUser } from '@/test/authFixtures'
import { render } from '@testing-library/react'
import { LoginPage } from './LoginPage'

const apiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...apiMocks }
})

function renderLogin(options?: { signupEmail?: string }) {
  const initialEntry = options?.signupEmail
    ? { pathname: '/login', state: { signupEmail: options.signupEmail } }
    : '/login'

  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <Routes>
          <Route
            path="/login"
            element={
              <AuthEntryGuard>
                <LoginPage />
              </AuthEntryGuard>
            }
          />
          {/* 기본 도착지는 상수를 따른다. 경로를 박아 두면 기본값을 바꿀 때마다 이 틀이 깨진다. */}
          <Route path={DEFAULT_RETURN_TARGET} element={<h1>기본 도착 화면</h1>} />
          <Route path="/records/*" element={<h1>내 기록 화면</h1>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    apiMocks.getCurrentUser.mockRejectedValue(
      new ApiClientError('api', '로그인이 필요합니다.', {
        code: 'AUTHENTICATION_REQUIRED',
      }),
    )
    apiMocks.login.mockResolvedValue(authenticatedUser)
  })

  it('가입 완료 이메일을 채우고 정규화한 이메일로 로그인한다', async () => {
    const user = userEvent.setup()
    renderLogin({ signupEmail: ' New.User@Example.COM ' })

    const email = await screen.findByLabelText('이메일')
    expect((email as HTMLInputElement).value).toBe('new.user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(apiMocks.login).toHaveBeenCalledWith(
      { email: 'new.user@example.com', password: 'password1234' },
      expect.any(AbortSignal),
    )
    expect(await screen.findByRole('heading', { name: '기본 도착 화면' })).toBeTruthy()
  })

  it('쳐 둔 이메일은 화면을 떠났다 돌아와도 남고 비밀번호는 남지 않는다', async () => {
    const user = userEvent.setup()
    const first = renderLogin()

    await user.type(await screen.findByLabelText('이메일'), 'new.user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')

    // 다른 화면에 갔다 오는 것과 같다.
    first.unmount()
    renderLogin()

    expect(await screen.findByLabelText('이메일')).toHaveProperty(
      'value',
      'new.user@example.com',
    )
    expect(screen.getByLabelText('비밀번호')).toHaveProperty('value', '')
  })

  it('로그인에 성공하면 적어 둔 이메일을 지운다', async () => {
    const user = userEvent.setup()
    renderLogin()

    await user.type(await screen.findByLabelText('이메일'), 'new.user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByRole('heading', { name: '기본 도착 화면' })
    expect(window.sessionStorage.getItem('thorp.auth.loginEmail')).toBeNull()
  })

  it('로그인 실패는 계정 존재 여부를 드러내지 않는 공통 안내를 표시한다', async () => {
    apiMocks.login.mockRejectedValue(
      new ApiClientError('api', '존재하지 않는 이메일입니다.', {
        code: 'INVALID_CREDENTIALS',
      }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(await screen.findByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(
      await screen.findByText('이메일 또는 비밀번호를 확인해 주세요.'),
    ).toBeTruthy()
    expect(screen.queryByText('존재하지 않는 이메일입니다.')).toBeNull()
  })

  it('처리 중에는 로그인 요청을 중복 제출하지 않는다', async () => {
    const loginRequest = deferred<typeof authenticatedUser>()
    apiMocks.login.mockReturnValue(loginRequest.promise)
    const user = userEvent.setup()
    renderLogin()

    await user.type(await screen.findByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    const submit = screen.getByRole('button', { name: '로그인' })
    await user.click(submit)
    await user.click(submit)

    expect(apiMocks.login).toHaveBeenCalledOnce()
    expect((screen.getByRole('button', { name: '로그인 중…' }) as HTMLButtonElement).disabled)
      .toBe(true)
    await act(async () => loginRequest.resolve(authenticatedUser))
  })

  it('이미 로그인한 사용자는 인증 입력 대신 홈으로 이동한다', async () => {
    apiMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
    renderLogin()

    expect(await screen.findByRole('heading', { name: '기본 도착 화면' })).toBeTruthy()
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
  })

  it('내 기록에서 온 사용자는 로그인 성공 뒤 저장된 위치로 한 번만 복귀한다', async () => {
    saveReturnTarget('/records/run-17?tab=trades#cost')
    const user = userEvent.setup()
    renderLogin()

    await user.type(await screen.findByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBeNull()
  })
})
