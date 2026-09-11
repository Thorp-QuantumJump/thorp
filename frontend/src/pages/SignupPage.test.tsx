import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { renderWithRouter } from '@/test/renderWithRouter'
import { SignupPage } from './SignupPage'
import { Route, Routes } from 'react-router-dom'

const apiMocks = vi.hoisted(() => ({
  requestEmailVerification: vi.fn(),
  confirmEmailVerification: vi.fn(),
  signup: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...apiMocks }
})

function LoginStateProbe() {
  const location = useLocation()
  return <output data-testid="login-state">{JSON.stringify(location.state)}</output>
}

function renderSignup() {
  return renderWithRouter(
    <StrictMode>
      <Routes>
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/login" element={<LoginStateProbe />} />
      </Routes>
    </StrictMode>,
    { initialEntries: ['/signup'] },
  )
}

async function completeEmailVerification(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('이메일'), ' New.User@Example.COM ')
  await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
  await screen.findByLabelText('인증번호')
  await user.type(screen.getByLabelText('인증번호'), '123456')
  await user.click(screen.getByRole('button', { name: '인증 확인' }))
  await screen.findByText('new.user@example.com 인증 완료')
}

async function completeAccountStep(user: ReturnType<typeof userEvent.setup>) {
  await completeEmailVerification(user)
  await user.type(screen.getByLabelText('비밀번호'), 'safe-password')
  await user.type(screen.getByLabelText('비밀번호 확인'), 'safe-password')
  await user.click(screen.getByRole('button', { name: '다음 단계' }))
  await screen.findByRole('heading', { name: '프로필 정보' })
}

describe('SignupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.requestEmailVerification.mockResolvedValue(undefined)
    apiMocks.confirmEmailVerification.mockResolvedValue(undefined)
    apiMocks.signup.mockResolvedValue({
      userId: 42,
      email: 'new.user@example.com',
      nickname: '새사용자',
      status: 'ACTIVE',
    })
  })

  it('잘못된 입력을 요청 전에 표시하고 첫 오류 입력으로 초점을 이동한다', async () => {
    const user = userEvent.setup()
    renderSignup()

    await user.click(screen.getByRole('button', { name: '다음 단계' }))

    expect(await screen.findByText('이메일을 입력해 주세요.')).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByLabelText('이메일'))
    expect(apiMocks.signup).not.toHaveBeenCalled()
  })

  it('처리 중 같은 인증번호 발송을 반복해도 요청을 한 번만 시작한다', async () => {
    let finishRequest: (() => void) | undefined
    apiMocks.requestEmailVerification.mockImplementation(
      () => new Promise<void>((resolve) => {
        finishRequest = resolve
      }),
    )
    const user = userEvent.setup()
    renderSignup()

    await user.type(screen.getByLabelText('이메일'), 'new@example.com')
    const sendButton = screen.getByRole('button', { name: '인증번호 받기' })
    await user.click(sendButton)
    await user.click(sendButton)
    await user.click(sendButton)

    expect(apiMocks.requestEmailVerification).toHaveBeenCalledOnce()
    finishRequest?.()
    await screen.findByLabelText('인증번호')
  })

  it('인증번호 확인 중에는 확인 요청을 중복 제출하지 않는다', async () => {
    let finishRequest: (() => void) | undefined
    apiMocks.confirmEmailVerification.mockImplementation(
      () => new Promise<void>((resolve) => {
        finishRequest = resolve
      }),
    )
    const user = userEvent.setup()
    renderSignup()

    await user.type(screen.getByLabelText('이메일'), 'new@example.com')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '123456')
    const confirmButton = screen.getByRole('button', { name: '인증 확인' })
    await user.click(confirmButton)
    await user.click(confirmButton)

    expect(apiMocks.confirmEmailVerification).toHaveBeenCalledOnce()
    expect((screen.getByRole('button', { name: '확인 중…' }) as HTMLButtonElement).disabled).toBe(
      true,
    )
    await act(async () => finishRequest?.())
    await screen.findByText('new@example.com 인증 완료')
  })

  it('인증·가입 성공 후 정규화한 이메일만 로그인 화면으로 전달한다', async () => {
    const user = userEvent.setup()
    renderSignup()
    await completeAccountStep(user)
    await user.type(screen.getByLabelText('닉네임'), '새사용자')
    await user.type(screen.getByLabelText('휴대폰 번호'), '01012345678')
    await user.click(screen.getByRole('button', { name: '계정 만들기' }))

    const state = await screen.findByTestId('login-state')
    expect(state.textContent).toBe('{"signupEmail":"new.user@example.com"}')
    expect(state.textContent).not.toContain('safe-password')
  })

  it('계정 생성 중에는 가입 요청을 중복 제출하지 않는다', async () => {
    let finishRequest: ((value: {
      userId: number
      email: string
      nickname: string
      status: 'ACTIVE'
    }) => void) | undefined
    apiMocks.signup.mockImplementation(
      () => new Promise((resolve) => {
        finishRequest = resolve
      }),
    )
    const user = userEvent.setup()
    renderSignup()
    await completeAccountStep(user)
    await user.type(screen.getByLabelText('닉네임'), '새사용자')
    await user.type(screen.getByLabelText('휴대폰 번호'), '01012345678')
    const submitButton = screen.getByRole('button', { name: '계정 만들기' })
    await user.click(submitButton)
    await user.click(submitButton)

    expect(apiMocks.signup).toHaveBeenCalledOnce()
    expect(
      (screen.getByRole('button', { name: '계정 생성 중…' }) as HTMLButtonElement).disabled,
    ).toBe(true)
    await act(async () => {
      finishRequest?.({
        userId: 42,
        email: 'new@example.com',
        nickname: '새사용자',
        status: 'ACTIVE',
      })
    })
    await screen.findByTestId('login-state')
  })

  it('서버의 전화번호 중복 오류를 해당 입력에 연결하고 다시 수정할 수 있게 한다', async () => {
    apiMocks.signup.mockRejectedValue(
      new ApiClientError('api', '이미 가입된 전화번호입니다.', {
        code: 'DUPLICATE_PHONE',
      }),
    )
    const user = userEvent.setup()
    renderSignup()
    await completeAccountStep(user)
    await user.type(screen.getByLabelText('닉네임'), '새사용자')
    await user.type(screen.getByLabelText('휴대폰 번호'), '01012345678')
    await user.click(screen.getByRole('button', { name: '계정 만들기' }))

    expect(await screen.findAllByText('이미 가입된 전화번호입니다.')).toHaveLength(2)
    expect(screen.getByLabelText('휴대폰 번호').getAttribute('aria-invalid')).toBe('true')
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByLabelText('휴대폰 번호'))
    })
  })

  it('개발용 미리보기에서는 서버 요청 없이 두 단계를 확인하고 로그인으로 이동한다', async () => {
    const user = userEvent.setup()
    renderSignup()

    await user.click(screen.getByRole('checkbox', { name: /서버 없이 UI 흐름 미리보기/ }))
    await user.type(screen.getByLabelText('이메일'), 'preview@example.com')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '123456')
    await user.click(screen.getByRole('button', { name: '인증 확인' }))
    await user.type(screen.getByLabelText('비밀번호'), 'safe-password')
    await user.type(screen.getByLabelText('비밀번호 확인'), 'safe-password')
    await user.click(screen.getByRole('button', { name: '다음 단계' }))
    await user.type(await screen.findByLabelText('닉네임'), '미리보기')
    await user.type(screen.getByLabelText('휴대폰 번호'), '01012345678')
    await user.click(screen.getByRole('button', { name: '계정 만들기' }))

    expect((await screen.findByTestId('login-state')).textContent).toBe(
      '{"signupEmail":"preview@example.com"}',
    )
    expect(apiMocks.requestEmailVerification).not.toHaveBeenCalled()
    expect(apiMocks.confirmEmailVerification).not.toHaveBeenCalled()
    expect(apiMocks.signup).not.toHaveBeenCalled()
  })
})
