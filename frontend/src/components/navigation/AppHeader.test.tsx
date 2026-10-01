import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext } from '@/auth/auth-context'
import { AppHeader } from './AppHeader'

function renderHeader() {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{ status: 'anonymous', user: null, login: vi.fn(), logout: vi.fn(), recheck: vi.fn(), updateNickname: vi.fn(), completeSignIn: vi.fn() }}>
        <AppHeader />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('AppHeader 전체 메뉴 초점', () => {
  it('서랍을 닫으면 보이는 햄버거 버튼으로 초점을 돌린다', () => {
    renderHeader()
    const menuButton = screen.getByRole('button', { name: '전체 메뉴 열기' })
    // jsdom 은 배치를 계산하지 않으므로 화면에 보이는 버튼처럼 상자 하나를 돌려준다.
    menuButton.getClientRects = () => [new DOMRect(0, 0, 40, 40)] as unknown as DOMRectList

    fireEvent.click(menuButton)
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(document.activeElement).toBe(menuButton)
  })

  it('버튼이 숨은 폭(헤더 메뉴가 보이는 폭)에서 닫으면 로고 링크로 초점을 돌린다', () => {
    renderHeader()
    // 상자가 없다는 것은 display: none 으로 숨었다는 뜻이다(721px 이상).
    const menuButton = screen.getByRole('button', { name: '전체 메뉴 열기' })
    menuButton.getClientRects = () => [] as unknown as DOMRectList

    fireEvent.click(menuButton)
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(document.activeElement).toBe(screen.getByRole('link', { name: 'Thorp 처음 화면' }))
  })
})
