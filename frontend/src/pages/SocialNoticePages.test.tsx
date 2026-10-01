import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { AuthEntryGuard } from '@/auth/AuthEntryGuard'
import { AuthProvider } from '@/auth/AuthProvider'
import { saveReturnTarget } from '@/auth/returnTarget'
import { authenticatedUser } from '@/test/authFixtures'
import { renderSocialRoutes } from '@/test/renderSocialRoutes'
import { LoginPage } from './LoginPage'

const authMocks = vi.hoisted(() => ({ getCurrentUser: vi.fn() }))
const socialMocks = vi.hoisted(() => ({
  startSocialLogin: vi.fn(),
  fetchSocialProviders: vi.fn(),
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

/** spec 014 — 거절·이미 연결·완료 화면과 로그인 화면의 소셜 안내(T086·T087, FR-022·FR-026). */
describe('소셜 안내 화면', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    authMocks.getCurrentUser.mockRejectedValue(anonymous())
    socialMocks.fetchSocialProviders.mockResolvedValue(['kakao', 'google'])
  })

  it('이메일 거절 화면은 다시 동의·이메일 가입·로그인으로 돌아가기 세 가지를 보여 준다', async () => {
    const user = userEvent.setup()
    renderSocialRoutes('/auth/social/email-required?provider=kakao')

    expect(await screen.findByRole('heading', { name: '이메일 정보가 필요해요.' })).toBeTruthy()
    const reprompt = screen.getByRole('button', { name: '다시 동의하고 카카오로 계속하기' })
    expect(screen.getByRole('link', { name: '이메일로 가입하기' }).getAttribute('href')).toBe(
      '/signup',
    )
    expect(
      screen.getByRole('link', { name: '로그인 화면으로 돌아가기' }).getAttribute('href'),
    ).toBe('/login')

    await user.click(reprompt)
    expect(socialMocks.startSocialLogin).toHaveBeenCalledWith('kakao', { reprompt: true })
  })

  it('제공자를 알 수 없으면 다시 동의 버튼 없이 나머지 두 길만 남긴다', async () => {
    renderSocialRoutes('/auth/social/email-required?provider=unknown')

    expect(await screen.findByRole('heading', { name: '이메일 정보가 필요해요.' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /다시 동의하고/ })).toBeNull()
    expect(screen.getByRole('link', { name: '이메일로 가입하기' })).toBeTruthy()
  })

  it('같은 제공자 충돌 화면은 연결을 시도하지 않고 로그인 방법만 안내한다', async () => {
    renderSocialRoutes('/auth/social/already-linked?provider=kakao')

    expect(
      await screen.findByRole('heading', { name: '이미 다른 카카오 계정과 연결돼 있어요.' }),
    ).toBeTruthy()
    // 틀의 뒤로 가기 링크와 안내의 버튼, 두 곳 모두 로그인 화면으로 간다.
    for (const link of screen.getAllByRole('link', { name: '로그인 화면으로' })) {
      expect(link.getAttribute('href')).toBe('/login')
    }
  })

  it('완료 화면은 로그인을 확인한 뒤 소셜 로그인 전에 있던 자리로 한 번만 돌아간다', async () => {
    saveReturnTarget('/records/searches')
    authMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
    renderSocialRoutes('/auth/social/complete')

    expect(await screen.findByRole('heading', { name: '내 기록 화면' })).toBeTruthy()
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBeNull()
  })

  it('완료 화면에서 로그인을 확인하지 못하면 로그인했다고 단정하지 않는다', async () => {
    renderSocialRoutes('/auth/social/complete')

    expect(
      await screen.findByText('로그인을 확인하지 못했습니다. 다시 로그인해 주세요.'),
    ).toBeTruthy()
  })

  it.each([
    ['cancelled', '소셜 로그인을 취소했습니다. 다른 방법으로 로그인할 수 있습니다.'],
    ['error', '소셜 로그인을 마치지 못했습니다. 처음부터 다시 시도해 주세요.'],
  ])('로그인 화면은 ?social=%s 안내를 띄우고 주소에서 지운다', async (value, message) => {
    let currentSearch = ''
    function SearchProbe() {
      currentSearch = window.location.search
      return null
    }
    render(
      <MemoryRouter initialEntries={[`/login?social=${value}`]}>
        <AuthProvider>
          <Routes>
            <Route
              path="/login"
              element={
                <AuthEntryGuard>
                  <LoginPage />
                  <SearchProbe />
                </AuthEntryGuard>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByText(message)).toBeTruthy()
    expect(currentSearch).not.toContain('social=')
  })

  it('로그인 화면은 켜진 제공자의 버튼만 정해진 순서로 그린다', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/login']}>
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
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    const buttons = await screen.findAllByRole('button', { name: /로 계속하기$/ })
    expect(buttons.map((button) => button.textContent)).toEqual([
      '카카오로 계속하기',
      'Google로 계속하기',
    ])
    await user.click(buttons[0])
    expect(socialMocks.startSocialLogin).toHaveBeenCalledWith('kakao')
  })

  it('제공자 목록을 받지 못하면 소셜 버튼을 그리지 않는다', async () => {
    socialMocks.fetchSocialProviders.mockResolvedValue([])
    render(
      <MemoryRouter initialEntries={['/login']}>
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
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /로 계속하기$/ })).toBeNull()
  })
})
