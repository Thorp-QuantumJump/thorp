import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import type { ResultConfig } from '@/types/result'
import { NextStepLinks } from './NextStepLinks'

/**
 * spec 006 T045a. 기록은 로그인한 사용자에게만 있으므로(FR-020) 두 갈래를 모두 고정한다.
 *
 * <p>비로그인에서 링크가 열리면 눌러 본 뒤에 로그인 화면으로 튕긴다 — 막는 이유를 먼저 말하는 것이 014의 규칙이다.
 */

const config = {
  strategyId: 'SECTOR_MOMENTUM',
  parameters: {},
  start: '2019-01-02',
  end: '2026-08-29',
  currency: 'KRW',
} as unknown as ResultConfig

const TOKEN = '3f1c9a7b4e0d83526af1c9d0b7e34512'

function renderWith(user: AuthContextValue['user']) {
  const auth = {
    user,
    status: 'authenticated',
    login: async () => {},
    logout: async () => {},
    refresh: async () => {},
  } as unknown as AuthContextValue
  render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <NextStepLinks config={config} resultToken={TOKEN} />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('NextStepLinks 기록으로 내보내기', () => {
  it('로그인 사용자에게는 이 결과의 기록 목록으로 가는 연결이다', () => {
    renderWith(authenticatedUser)

    const link = screen.getByRole('link', { name: '기록으로 내보내기' })
    expect(link.getAttribute('href')).toBe(`/records?resultToken=${TOKEN}`)
  })

  it('비로그인에게는 로그인이 필요하다는 사유와 함께 막힌다', () => {
    renderWith(null)

    expect(screen.queryByRole('link', { name: '기록으로 내보내기' })).toBeNull()
    const button = screen.getByRole('button', { name: '기록으로 내보내기' })
    expect(button.hasAttribute('disabled')).toBe(true)
    const reasonId = button.getAttribute('aria-describedby')
    expect(document.getElementById(reasonId ?? '')?.textContent).toContain('로그인이 필요합니다')
  })

  it('011 경제환경 버튼은 그대로 막혀 있다', () => {
    renderWith(authenticatedUser)

    expect(
      screen.getByRole('button', { name: '경제환경이 비슷했던 과거 보기' }).hasAttribute('disabled'),
    ).toBe(true)
  })
})
