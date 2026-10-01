import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { AuthStatus } from '@/components/auth/AuthStatus'

function renderHeader(status: AuthContextValue['status']) {
  const value: AuthContextValue = {
    status,
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  }
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={value}><AppHeader /></AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('authentication status accessibility', () => {
  it('360px에서도 확인 중 상태를 live status로 전달한다', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 360 })
    renderHeader('checking')
    expect(screen.getByRole('status').textContent).toContain('확인 중')
  })

  it('만료 상태에서 재로그인 행동을 제공한다', () => {
    renderHeader('expired')
    expect(screen.getByRole('status').textContent).toContain('로그인이 만료되었습니다')
    expect(screen.getByRole('link', { name: '다시 로그인' })).toBeTruthy()
  })

  it('로그아웃 미확인 상태에서 경고와 두 복구 행동을 제공한다', () => {
    renderHeader('logoutUnconfirmed')
    expect(screen.getByRole('alert').textContent).toContain('로그아웃 상태를 확인하지 못했습니다')
    expect(screen.getByRole('button', { name: '로그아웃 다시 시도' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인 상태 다시 확인' })).toBeTruthy()
  })

  it('요청 중과 오류 메시지를 live region으로 원자적으로 알린다', () => {
    const { rerender } = render(<AuthStatus message="요청을 처리하고 있습니다." />)
    const pendingStatus = screen.getByRole('status')
    expect(pendingStatus.textContent).toContain('요청을 처리하고 있습니다.')
    expect(pendingStatus.getAttribute('aria-atomic')).toBe('true')

    rerender(<AuthStatus message="요청을 처리하지 못했습니다." tone="error" />)
    expect(screen.getByRole('alert').textContent).toContain('요청을 처리하지 못했습니다.')
  })
})
