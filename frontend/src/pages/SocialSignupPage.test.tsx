import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { saveReturnTarget } from '@/auth/returnTarget'
import { authenticatedUser } from '@/test/authFixtures'
import { pendingView, renderSocialRoutes } from '@/test/renderSocialRoutes'

const authMocks = vi.hoisted(() => ({ getCurrentUser: vi.fn() }))
const socialMocks = vi.hoisted(() => ({
  getPendingSocial: vi.fn(),
  socialSignup: vi.fn(),
  cancelPendingSocial: vi.fn(),
  sendSocialVerificationCode: vi.fn(),
  confirmSocialEmail: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...authMocks }
})
vi.mock('@/api/socialAuth', async () => {
  const actual = await vi.importActual<typeof import('@/api/socialAuth')>('@/api/socialAuth')
  return { ...actual, ...socialMocks }
})

const anonymous = () =>
  new ApiClientError('api', '로그인이 필요합니다.', { code: 'AUTHENTICATION_REQUIRED' })

/** spec 014 US6 — 소셜 신규 가입의 추가 정보 화면과 이메일 확인 화면(T086). */
describe('SocialSignupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    authMocks.getCurrentUser.mockRejectedValue(anonymous())
    socialMocks.getPendingSocial.mockResolvedValue(pendingView())
    socialMocks.cancelPendingSocial.mockResolvedValue(undefined)
  })

  it('제공자 이메일을 고칠 수 없게 보여 주고 기준에 맞는 닉네임을 미리 채운다', async () => {
    renderSocialRoutes('/signup/social')

    const email = (await screen.findByLabelText('이메일')) as HTMLInputElement
    expect(email.value).toBe('member@example.com')
    expect(email.readOnly).toBe(true)
    expect((screen.getByLabelText('닉네임') as HTMLInputElement).value).toBe('카카오닉')
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
  })

  it('가입하면 로그인 상태가 되어 소셜 로그인 전에 있던 자리로 돌아간다', async () => {
    const user = userEvent.setup()
    saveReturnTarget('/records/searches')
    socialMocks.socialSignup.mockImplementation(async () => {
      authMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
      return authenticatedUser
    })
    renderSocialRoutes('/signup/social')

    await screen.findByLabelText('닉네임')
    // 가입에서 휴대폰 번호를 묻지 않는다(spec 014 2026-10-02).
    expect(screen.queryByLabelText('휴대폰 번호')).toBeNull()
    await user.click(screen.getByRole('button', { name: '가입하기' }))

    expect(socialMocks.socialSignup).toHaveBeenCalledWith(
      { nickname: '카카오닉' },
      expect.any(AbortSignal),
    )
    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
  })

  it('형식이 맞지 않으면 요청하지 않고 그 칸으로 초점을 옮긴다', async () => {
    const user = userEvent.setup()
    renderSocialRoutes('/signup/social')

    const nickname = await screen.findByLabelText('닉네임')
    await user.clear(nickname)
    await user.type(nickname, '가')
    await user.click(screen.getByRole('button', { name: '가입하기' }))

    expect(socialMocks.socialSignup).not.toHaveBeenCalled()
    expect(screen.getByText('닉네임은 2~20자로 입력해 주세요.')).toBeTruthy()
    expect(document.activeElement).toBe(nickname)
  })

  it('처리 중에 여러 번 눌러도 가입 요청은 한 번만 시작한다', async () => {
    const user = userEvent.setup()
    socialMocks.socialSignup.mockReturnValue(new Promise(() => undefined))
    renderSocialRoutes('/signup/social')

    await screen.findByLabelText('닉네임')
    const submit = screen.getByRole('button', { name: '가입하기' })
    await user.click(submit)
    await user.click(screen.getByRole('button', { name: '가입하는 중…' }))
    await user.click(screen.getByRole('button', { name: '가입하는 중…' }))

    expect(socialMocks.socialSignup).toHaveBeenCalledTimes(1)
  })

  it('가입 중 연결이 필요하다는 응답이면 계정 연결 확인 화면으로 간다', async () => {
    const user = userEvent.setup()
    socialMocks.socialSignup.mockRejectedValue(
      new ApiClientError('api', '이 번호로 가입된 계정이 있습니다.', { code: 'SOCIAL_LINK_REQUIRED' }),
    )
    renderSocialRoutes('/signup/social')

    await screen.findByLabelText('닉네임')
    socialMocks.getPendingSocial.mockResolvedValue(
      pendingView({
        stage: 'LINK',
        linkReason: 'PHONE',
        linkTargetMaskedEmail: 'o***@example.com',
        linkMethods: ['PASSWORD', 'EMAIL_CODE'],
      }),
    )
    await user.click(screen.getByRole('button', { name: '가입하기' }))

    expect(
      await screen.findByRole('heading', { name: '이 번호로 가입된 계정이 있어요.' }),
    ).toBeTruthy()
  })

  it('대기 상태가 만료됐으면 입력 칸 없이 처음부터 다시 하라고 안내한다', async () => {
    socialMocks.getPendingSocial.mockRejectedValue(
      new ApiClientError('api', '시간이 지났습니다.', { code: 'SOCIAL_PENDING_NOT_FOUND' }),
    )
    renderSocialRoutes('/signup/social')

    expect(
      await screen.findByText('소셜 로그인 시간이 지났습니다. 처음부터 다시 시도해 주세요.'),
    ).toBeTruthy()
    expect(screen.queryByLabelText('휴대폰 번호')).toBeNull()
  })

  it('이메일 확인이 남은 대기 상태면 이메일 확인 화면으로 옮긴다', async () => {
    socialMocks.getPendingSocial.mockResolvedValue(pendingView({ stage: 'EMAIL_VERIFICATION' }))
    renderSocialRoutes('/signup/social')

    expect(await screen.findByRole('heading', { name: '이메일을 한 번 확인할게요.' })).toBeTruthy()
  })

  it('이메일 확인 화면은 인증번호를 받아 확인한 뒤 가입 화면으로 이어 간다', async () => {
    const user = userEvent.setup()
    socialMocks.getPendingSocial.mockResolvedValue(
      pendingView({ provider: 'naver', stage: 'EMAIL_VERIFICATION' }),
    )
    socialMocks.sendSocialVerificationCode.mockResolvedValue(undefined)
    socialMocks.confirmSocialEmail.mockImplementation(async () => {
      socialMocks.getPendingSocial.mockResolvedValue(pendingView({ provider: 'naver' }))
      return pendingView({ provider: 'naver' })
    })
    renderSocialRoutes('/auth/social/verify-email')

    await user.click(await screen.findByRole('button', { name: '인증번호 받기' }))
    expect(
      await screen.findByText('인증번호를 보냈습니다. 3분 안에 입력해 주세요.'),
    ).toBeTruthy()
    await user.type(screen.getByLabelText('인증번호'), '123456')
    await user.click(screen.getByRole('button', { name: '확인하고 계속하기' }))

    expect(socialMocks.confirmSocialEmail).toHaveBeenCalledWith('123456', expect.any(AbortSignal))
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '가입까지 한 걸음 남았어요.' })).toBeTruthy(),
    )
  })

  it('이메일 확인 화면은 틀린 번호의 서버 안내를 그 칸에 보여 준다', async () => {
    const user = userEvent.setup()
    socialMocks.getPendingSocial.mockResolvedValue(pendingView({ stage: 'EMAIL_VERIFICATION' }))
    socialMocks.sendSocialVerificationCode.mockResolvedValue(undefined)
    socialMocks.confirmSocialEmail.mockRejectedValue(
      new ApiClientError('api', '인증번호를 다시 확인해 주세요.', {
        code: 'EMAIL_VERIFICATION_INVALID',
      }),
    )
    renderSocialRoutes('/auth/social/verify-email')

    await user.click(await screen.findByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '000000')
    await user.click(screen.getByRole('button', { name: '확인하고 계속하기' }))

    expect(await screen.findByText('인증번호를 다시 확인해 주세요.')).toBeTruthy()
  })
})
