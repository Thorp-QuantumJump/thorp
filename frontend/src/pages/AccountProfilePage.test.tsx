import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import { AccountProfilePage } from './AccountProfilePage'
import { AccountProfilePreviewPage } from './AccountProfilePreviewPage'

function renderPage() {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{
        status: 'authenticated',
        user: authenticatedUser,
        login: vi.fn(),
        logout: vi.fn(),
        recheck: vi.fn(),
      }}>
        <AccountProfilePage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('AccountProfilePage', () => {
  it('개발용 미리보기는 로그인 없이 예시 정보와 저장 불가 안내를 보여 준다', () => {
    render(
      <MemoryRouter>
        <AccountProfilePreviewPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('note').textContent).toContain('실제로 로그인한 상태가 아니며')
    expect((screen.getByLabelText('이메일') as HTMLInputElement).value).toBe('preview@example.com')
    expect((screen.getByLabelText('닉네임') as HTMLInputElement).value).toBe('미리보기 사용자')
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

  it('이메일은 보기만, 닉네임만 수정 칸으로 두고 휴대폰 번호 칸이나 변경 저장을 꾸며내지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    expect((screen.getByLabelText('이메일') as HTMLInputElement).value).toBe('user@example.com')
    expect((screen.getByLabelText('이메일') as HTMLInputElement).readOnly).toBe(true)
    expect((screen.getByLabelText('닉네임') as HTMLInputElement).value).toBe('쏘프사용자')
    expect(screen.queryByLabelText('휴대폰 번호')).toBeNull()

    await user.clear(screen.getByLabelText('닉네임'))
    await user.click(screen.getByRole('button', { name: '입력값 확인' }))
    expect(screen.getByText('닉네임은 2~20자로 입력해 주세요.')).toBeTruthy()

    await user.type(screen.getByLabelText('닉네임'), '새닉네임')
    await user.click(screen.getByRole('button', { name: '입력값 확인' }))
    expect(screen.getByRole('status').textContent).toContain('변경 내용은 저장되지 않았습니다')
  })
})
