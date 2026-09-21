import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthContext } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { resultDetailCompleted } from '@/test/resultFixtures'
import { NextStepLinks } from './NextStepLinks'

const base = 'a'.repeat(32)
const target = 'b'.repeat(32)

const loggedInAuth = {
  ...anonymousAuthContext,
  user: authenticatedUser,
  status: 'authenticated',
} as typeof anonymousAuthContext

function renderLinks(
  props: Partial<Parameters<typeof NextStepLinks>[0]> = {},
  auth = anonymousAuthContext,
) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <NextStepLinks config={resultDetailCompleted.config} token={base} {...props} />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('결과 화면의 011 진입', () => {
  it('비교 대상이 열람 가능하면 compare를 함께 싣는다', () => {
    renderLinks({ compareToken: target, compareReady: true })
    expect(
      screen.getByRole('link', { name: '경제환경이 비슷했던 과거 보기' }).getAttribute('href'),
    ).toBe(`/regime-link?base=${base}&compare=${target}`)
  })

  it('비교 대상이 아직 준비되지 않았으면 base만 싣는다', () => {
    renderLinks({ compareToken: target, compareReady: false })
    expect(
      screen.getByRole('link', { name: '경제환경이 비슷했던 과거 보기' }).getAttribute('href'),
    ).toBe(`/regime-link?base=${base}`)
  })
})

/**
 * spec 006 T045a. 기록은 로그인한 사용자에게만 있으므로(FR-020) 두 갈래를 모두 고정한다.
 *
 * <p>비로그인에서 링크가 열리면 눌러 본 뒤에 로그인 화면으로 튕긴다 — 막는 이유를 먼저 말하는 것이 014의 규칙이다.
 */
describe('NextStepLinks 기록으로 내보내기', () => {
  it('로그인 사용자에게는 이 결과의 기록 목록으로 가는 연결이다', () => {
    renderLinks({}, loggedInAuth)

    expect(screen.getByRole('link', { name: '기록으로 내보내기' }).getAttribute('href')).toBe(
      `/records?resultToken=${base}`,
    )
  })

  it('비로그인에게는 로그인이 필요하다는 사유와 함께 막힌다', () => {
    renderLinks()

    expect(screen.queryByRole('link', { name: '기록으로 내보내기' })).toBeNull()
    const button = screen.getByRole('button', { name: '기록으로 내보내기' })
    expect(button.hasAttribute('disabled')).toBe(true)
    const reasonId = button.getAttribute('aria-describedby')
    expect(document.getElementById(reasonId ?? '')?.textContent).toContain('로그인이 필요합니다')
  })

  it('011 진입은 006 변경과 무관하게 그대로 열려 있다', () => {
    renderLinks({}, loggedInAuth)

    expect(screen.getByRole('link', { name: '경제환경이 비슷했던 과거 보기' })).toBeTruthy()
  })
})
