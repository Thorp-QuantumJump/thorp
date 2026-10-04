import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import { AccountProfilePage } from './AccountProfilePage'
import { AccountProfilePreviewPage } from './AccountProfilePreviewPage'

function renderPage(updateNickname: AuthContextValue['updateNickname'] = vi.fn()) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{
        status: 'authenticated',
        user: authenticatedUser,
        login: vi.fn(),
        logout: vi.fn(),
        recheck: vi.fn(),
        updateNickname,
      }}>
        <AccountProfilePage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('AccountProfilePage', () => {
  it('개발용 미리보기는 로그인 없이 예시 정보를 보여 주고 저장하지 않는다', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <AccountProfilePreviewPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('note').textContent).toContain('실제로 로그인한 상태가 아니며')
    expect((screen.getByLabelText('이메일') as HTMLInputElement).value).toBe('preview@example.com')
    expect((screen.getByLabelText('닉네임') as HTMLInputElement).value).toBe('미리보기 사용자')

    await user.clear(screen.getByLabelText('닉네임'))
    await user.type(screen.getByLabelText('닉네임'), '새닉네임')
    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))
    expect(screen.getByRole('status').textContent).toContain('미리보기 화면이라 저장하지 않았습니다')
  })

  it('닉네임은 회원정보로, 내 기록은 기존 기록 경로로 연결한다', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('link', { name: '쏘프사용자 회원정보 수정' }).getAttribute('href'))
      .toBe('/account/profile')
    expect(screen.getByRole('link', { name: '내 기록' }).getAttribute('href')).toBe('/records')

    await user.click(screen.getByRole('button', { name: '전체 메뉴 열기' }))
    expect(screen.getByRole('dialog', { name: '메뉴' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '쏘프사용자' }).getAttribute('href'))
      .toBe('/account/profile')
  })

  it('이메일은 보기만, 닉네임만 수정 칸으로 두고 휴대폰 번호 칸은 두지 않는다', () => {
    renderPage()

    expect(screen.getByRole('heading', { level: 1, name: '회원정보 수정' })).toBeTruthy()
    expect(screen.getByText('회원 정보를 확인하고 수정할 내용을 입력할 수 있습니다.')).toBeTruthy()
    expect((screen.getByLabelText('이메일') as HTMLInputElement).value).toBe('user@example.com')
    expect((screen.getByLabelText('이메일') as HTMLInputElement).readOnly).toBe(true)
    expect((screen.getByLabelText('닉네임') as HTMLInputElement).value).toBe('쏘프사용자')
    expect(screen.queryByLabelText('휴대폰 번호')).toBeNull()
  })

  it('앞뒤 공백을 뗀 닉네임으로 저장하고 성공을 알린다', async () => {
    const user = userEvent.setup()
    const updateNickname = vi.fn().mockResolvedValue({ ...authenticatedUser, nickname: '새닉네임' })
    renderPage(updateNickname)

    await user.clear(screen.getByLabelText('닉네임'))
    await user.type(screen.getByLabelText('닉네임'), '  새닉네임  ')
    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))

    expect(updateNickname).toHaveBeenCalledWith('새닉네임')
    expect((await screen.findByRole('status')).textContent).toContain('닉네임을 바꿨습니다')
  })

  it('길이 규칙에 맞지 않거나 지금과 같은 닉네임은 서버에 보내지 않는다', async () => {
    const user = userEvent.setup()
    const updateNickname = vi.fn()
    renderPage(updateNickname)

    await user.clear(screen.getByLabelText('닉네임'))
    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))
    expect(screen.getByText('닉네임은 2~20자로 입력해 주세요.')).toBeTruthy()

    await user.type(screen.getByLabelText('닉네임'), '쏘프사용자')
    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))
    expect(screen.getByText('지금 쓰는 닉네임과 같습니다.')).toBeTruthy()

    expect(updateNickname).not.toHaveBeenCalled()
  })

  it('서버가 입력값을 거절하면 닉네임 칸에, 그 밖의 실패는 화면에 알린다', async () => {
    const user = userEvent.setup()
    const updateNickname = vi.fn()
      .mockRejectedValueOnce(
        new ApiClientError('api', '입력값이 올바르지 않습니다.', { code: 'INVALID_INPUT' }),
      )
      .mockRejectedValueOnce(
        new ApiClientError('network', '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'),
      )
    renderPage(updateNickname)

    await user.clear(screen.getByLabelText('닉네임'))
    await user.type(screen.getByLabelText('닉네임'), '새닉네임')
    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))
    expect(await screen.findByText('닉네임은 2~20자로 입력해 주세요.')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '닉네임 저장' }))
    expect((await screen.findByRole('alert')).textContent).toContain('서버에 연결할 수 없습니다')
    expect(screen.queryByRole('status')).toBeNull()
  })
})
