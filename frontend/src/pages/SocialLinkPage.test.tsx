import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { saveReturnTarget } from '@/auth/returnTarget'
import { authenticatedUser } from '@/test/authFixtures'
import { pendingView, renderSocialRoutes } from '@/test/renderSocialRoutes'

const authMocks = vi.hoisted(() => ({ getCurrentUser: vi.fn() }))
const socialMocks = vi.hoisted(() => ({
  getPendingSocial: vi.fn(),
  socialLink: vi.fn(),
  declineSocialLink: vi.fn(),
  cancelPendingSocial: vi.fn(),
  sendSocialVerificationCode: vi.fn(),
  startSocialLogin: vi.fn(),
}))

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...authMocks }
})
vi.mock('@/api/socialAuth', async () => {
  const actual = await vi.importActual<typeof import('@/api/socialAuth')>('@/api/socialAuth')
  return { ...actual, ...socialMocks }
})

const linkPending = pendingView({
  stage: 'LINK',
  linkReason: 'EMAIL',
  linkTargetMaskedEmail: 'm***@example.com',
  linkMethods: ['PASSWORD', 'EMAIL_CODE', 'SOCIAL_GOOGLE'],
})

/** spec 014 US7 — 같은 이메일·휴대폰 번호의 기존 계정 연결 확인(T087). */
describe('SocialLinkPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    authMocks.getCurrentUser.mockRejectedValue(
      new ApiClientError('api', '로그인이 필요합니다.', { code: 'AUTHENTICATION_REQUIRED' }),
    )
    socialMocks.getPendingSocial.mockResolvedValue(linkPending)
    socialMocks.declineSocialLink.mockResolvedValue(undefined)
    socialMocks.sendSocialVerificationCode.mockResolvedValue(undefined)
  })

  it('가린 기존 계정 이메일과 그 계정에 쓸 수 있는 확인 방법만 보여 준다', async () => {
    renderSocialRoutes('/auth/social/link')

    expect(await screen.findByText('m***@example.com')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '이미 가입된 계정이 있어요.' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '기존 비밀번호로 확인' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '기존 계정 이메일로 확인' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Google로 확인하기' })).toBeTruthy()
    // 서버가 내놓지 않은 방법은 그리지 않는다.
    expect(screen.queryByRole('button', { name: '이 계정에 연결하기' })).toBeNull()
  })

  it('비밀번호로 확인하면 연결하고 로그인 상태로 원래 자리에 돌아간다', async () => {
    const user = userEvent.setup()
    saveReturnTarget('/records/searches')
    socialMocks.socialLink.mockResolvedValue(authenticatedUser)
    renderSocialRoutes('/auth/social/link')

    await user.type(await screen.findByLabelText('비밀번호'), 'password123')
    await user.click(screen.getAllByRole('button', { name: '확인하고 연결하기' })[0])

    expect(socialMocks.socialLink).toHaveBeenCalledWith(
      { method: 'PASSWORD', password: 'password123' },
      expect.any(AbortSignal),
    )
    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
  })

  it('비밀번호가 틀리면 연결하지 않고 다시 시도할 수 있게 안내한다', async () => {
    const user = userEvent.setup()
    socialMocks.socialLink.mockRejectedValue(
      new ApiClientError('api', '이메일 또는 비밀번호를 확인해 주세요.', {
        code: 'INVALID_CREDENTIALS',
      }),
    )
    renderSocialRoutes('/auth/social/link')

    await user.type(await screen.findByLabelText('비밀번호'), 'wrong')
    await user.click(screen.getAllByRole('button', { name: '확인하고 연결하기' })[0])

    expect(await screen.findByText('비밀번호를 다시 확인해 주세요.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '이미 가입된 계정이 있어요.' })).toBeTruthy()
  })

  it('확인 상한을 넘어 대기 상태가 사라지면 폼 대신 처음부터 다시 하라는 안내로 바뀐다', async () => {
    const user = userEvent.setup()
    socialMocks.socialLink.mockRejectedValue(
      new ApiClientError('api', '확인 시도 횟수를 넘었습니다.', { code: 'SOCIAL_VERIFICATION_LIMIT' }),
    )
    renderSocialRoutes('/auth/social/link')

    await user.type(await screen.findByLabelText('비밀번호'), 'wrong')
    // 서버는 상한을 넘기면서 대기 상태를 버렸다. 다시 읽으면 없다.
    socialMocks.getPendingSocial.mockRejectedValue(
      new ApiClientError('api', '없음', { code: 'SOCIAL_PENDING_NOT_FOUND' }),
    )
    await user.click(screen.getAllByRole('button', { name: '확인하고 연결하기' })[0])

    expect(
      await screen.findByText('소셜 로그인 시간이 지났습니다. 처음부터 다시 시도해 주세요.'),
    ).toBeTruthy()
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
  })

  it('기존 계정 이메일로 인증번호를 받아 확인할 수 있다', async () => {
    const user = userEvent.setup()
    socialMocks.socialLink.mockResolvedValue(authenticatedUser)
    renderSocialRoutes('/auth/social/link')

    await user.click(await screen.findByRole('button', { name: '인증번호 받기' }))
    expect(
      await screen.findByText(
        'm***@example.com로 인증번호를 보냈습니다. 3분 안에 입력해 주세요.',
      ),
    ).toBeTruthy()
    await user.type(screen.getByLabelText('인증번호'), '654321')
    await user.click(screen.getAllByRole('button', { name: '확인하고 연결하기' })[1])

    expect(socialMocks.socialLink).toHaveBeenCalledWith(
      { method: 'EMAIL_CODE', verificationCode: '654321' },
      expect.any(AbortSignal),
    )
  })

  it('이미 연결된 소셜 계정으로 확인하면 intent=link로 그 제공자에게 간다', async () => {
    const user = userEvent.setup()
    renderSocialRoutes('/auth/social/link')

    await user.click(await screen.findByRole('button', { name: 'Google로 확인하기' }))

    expect(socialMocks.startSocialLogin).toHaveBeenCalledWith('google', { intent: 'link' })
  })

  it('같은 대기 상태에서 이메일을 확인했으면 버튼 하나로 연결한다', async () => {
    const user = userEvent.setup()
    socialMocks.getPendingSocial.mockResolvedValue({
      ...linkPending,
      linkMethods: ['VERIFIED_EMAIL', 'EMAIL_CODE'],
    })
    socialMocks.socialLink.mockResolvedValue(authenticatedUser)
    renderSocialRoutes('/auth/social/link')

    await user.click(await screen.findByRole('button', { name: '이 계정에 연결하기' }))

    expect(socialMocks.socialLink).toHaveBeenCalledWith(
      { method: 'VERIFIED_EMAIL' },
      expect.any(AbortSignal),
    )
  })

  it('이메일이 겹쳐 온 연결을 하지 않으면 로그인 화면으로 돌아간다', async () => {
    const user = userEvent.setup()
    renderSocialRoutes('/auth/social/link')

    await user.click(await screen.findByRole('button', { name: '연결하지 않기' }))

    expect(socialMocks.declineSocialLink).toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(socialMocks.socialLink).not.toHaveBeenCalled()
  })

  it('번호가 겹쳐 온 연결을 하지 않으면 다른 번호로 가입하는 화면으로 돌아간다', async () => {
    const user = userEvent.setup()
    socialMocks.getPendingSocial.mockResolvedValue({ ...linkPending, linkReason: 'PHONE' })
    renderSocialRoutes('/auth/social/link')

    expect(
      await screen.findByRole('heading', { name: '이 번호로 가입된 계정이 있어요.' }),
    ).toBeTruthy()
    socialMocks.getPendingSocial.mockResolvedValue(pendingView())
    await user.click(screen.getByRole('button', { name: '연결하지 않고 다른 번호로 가입하기' }))

    expect(
      await screen.findByRole('heading', { name: '가입까지 한 걸음 남았어요.' }),
    ).toBeTruthy()
  })

  it('번호가 겹쳐 온 연결은 기존 계정 이메일을 보여 주지 않고 가입할 때 쓴 이메일로 보냈다고만 알린다', async () => {
    const user = userEvent.setup()
    socialMocks.getPendingSocial.mockResolvedValue({
      ...linkPending,
      linkReason: 'PHONE',
      linkTargetMaskedEmail: null,
      linkMethods: ['PASSWORD', 'EMAIL_CODE'],
    })
    renderSocialRoutes('/auth/social/link')

    expect(
      await screen.findByRole('heading', { name: '이 번호로 가입된 계정이 있어요.' }),
    ).toBeTruthy()
    expect(screen.queryByText('기존 계정')).toBeNull()
    expect(screen.queryByText(/\*\*\*@/)).toBeNull()
    expect(screen.queryByRole('heading', { name: '이미 연결된 소셜 계정으로 확인' })).toBeNull()

    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    expect(
      await screen.findByText('가입할 때 쓴 이메일로 인증번호를 보냈습니다. 3분 안에 입력해 주세요.'),
    ).toBeTruthy()
  })

  it('다른 소셜 계정 확인이 실패하고 돌아오면 그 사실을 알린다', async () => {
    renderSocialRoutes('/auth/social/link?social=error')

    expect(
      await screen.findByText(
        '그 소셜 계정으로는 이 계정을 확인하지 못했습니다. 다른 방법으로 확인해 주세요.',
      ),
    ).toBeTruthy()
  })
})
