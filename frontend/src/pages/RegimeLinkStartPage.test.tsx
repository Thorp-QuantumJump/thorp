import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authenticatedUser } from '@/test/authFixtures'
import type { AuthStateStatus } from '@/types/auth'
import { RegimeLinkStartPage } from './RegimeLinkStartPage'

const authMock = vi.hoisted(() => ({ status: 'authenticated' as AuthStateStatus }))

vi.mock('@/auth/auth-context', () => ({
  useAuth: () => ({
    status: authMock.status,
    user: authMock.status === 'authenticated' ? authenticatedUser : null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }),
}))

vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => null }))

vi.mock('@/api/regimeLink', () => ({ fetchCaseWindow: vi.fn() }))

const { fetchCaseWindow } = await import('@/api/regimeLink')

const CASE_ID = 'FIELD:LABOR:2026-07-01:MACRO-1:REG-3:REGIME-V1~2012-03'

/** 이동한 주소를 그대로 읽어 본다. 인계가 주소로 이어지는 것이 이 화면의 일이다. */
function Landed({ label }: { label: string }) {
  const location = useLocation()
  return <p>{`${label}:${location.pathname}${location.search}`}</p>
}

function renderStart(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/regime-link/start" element={<RegimeLinkStartPage />} />
        <Route path="/diagnosis/sectors" element={<Landed label="002" />} />
        <Route path="/login" element={<Landed label="로그인" />} />
        <Route path="/market-flow" element={<Landed label="시장 흐름" />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('011 시작 화면 (진입 계약 §2)', () => {
  beforeEach(() => {
    authMock.status = 'authenticated'
    vi.mocked(fetchCaseWindow).mockReset()
    window.sessionStorage.clear()
  })

  it('사례가 확인되면 002로 사례와 섹터를 주소에 실어 넘긴다', async () => {
    vi.mocked(fetchCaseWindow).mockResolvedValue({
      basis: { kind: 'HISTORICAL_REVISION' },
      caseId: CASE_ID,
      endMonth: '2012-03',
      postWindow: {
        d0: '2012-04-02', d21: '2012-05-02', d63: '2012-07-03', lastAvailable: '2012-07-03',
        completedTradingDays: 63, complete21: true, complete63: true,
      },
    })

    renderStart(`/regime-link/start?case=${encodeURIComponent(CASE_ID)}&sector=SEMICONDUCTOR`)

    const landed = await screen.findByText(/^002:/)
    expect(landed.textContent).toContain('/diagnosis/sectors')
    expect(new URLSearchParams(landed.textContent?.split('?')[1] ?? '').get('case')).toBe(CASE_ID)
    expect(landed.textContent).toContain('sector=SEMICONDUCTOR')
  })

  /** FR-025·014 FR-011: 주소를 인자로 싣지 않고 복귀 위치를 저장한 뒤 로그인으로 보낸다. */
  it('익명이면 같은 주소를 복귀 위치로 저장하고 로그인으로 보낸다', async () => {
    authMock.status = 'anonymous'

    renderStart(`/regime-link/start?case=${encodeURIComponent(CASE_ID)}`)

    const landed = await screen.findByText(/^로그인:/)
    expect(landed.textContent).not.toContain('returnTo')
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe(
      `/regime-link/start?case=${encodeURIComponent(CASE_ID)}`,
    )
    expect(fetchCaseWindow).not.toHaveBeenCalled()
  })

  it('사례 형식이 틀리면 시장 흐름으로 안내하고 이유를 한 문장으로 말한다', async () => {
    renderStart('/regime-link/start?case=only-a-key')

    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('link', { name: '시장 흐름으로 가기' }).getAttribute('href')).toBe('/market-flow')
    expect(fetchCaseWindow).not.toHaveBeenCalled()
  })

  it('사례가 아예 없으면 빈 화면을 만들지 않는다', async () => {
    renderStart('/regime-link/start')

    expect((await screen.findByRole('alert')).textContent).toContain('사례가 주소에 없습니다')
  })

  it('없는 사례(404)는 다시 고르라고 안내한다', async () => {
    vi.mocked(fetchCaseWindow).mockRejectedValue(
      Object.assign(new Error('없음'), { status: 404, code: 'RESOURCE_NOT_FOUND' }),
    )

    renderStart(`/regime-link/start?case=${encodeURIComponent(CASE_ID)}`)

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('더는 찾을 수 없습니다'))
    expect(screen.queryByText(/^002:/)).toBeNull()
  })
})
