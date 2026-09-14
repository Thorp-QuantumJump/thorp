import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthStateStatus } from '@/types/auth'
import { authenticatedUser } from '@/test/authFixtures'
import { ProtectedRoute } from './ProtectedRoute'

const authMock = vi.hoisted(() => ({
  status: 'anonymous' as AuthStateStatus,
}))

vi.mock('./auth-context', () => ({
  useAuth: () => ({
    status: authMock.status,
    user: authMock.status === 'authenticated' ? authenticatedUser : null,
    login: vi.fn(),
    recheck: vi.fn(),
  }),
}))

function renderProtected(initialEntry = '/records/analysis-42?view=trades#loss') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route
          path="/records/*"
          element={
            <ProtectedRoute>
              <h1>보호된 내 기록</h1>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<h1>로그인 화면</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    authMock.status = 'anonymous'
    window.sessionStorage.clear()
  })

  it('인증 확인 중에는 보호된 내용을 표시하지 않는다', () => {
    authMock.status = 'checking'
    renderProtected()

    expect(screen.getByRole('status').textContent).toContain('로그인 상태를 확인')
    expect(screen.queryByText('보호된 내 기록')).toBeNull()
  })

  it('로그인 사용자는 보호된 내 기록을 볼 수 있다', () => {
    authMock.status = 'authenticated'
    renderProtected()

    expect(screen.getByRole('heading', { name: '보호된 내 기록' })).toBeTruthy()
  })

  it.each(['anonymous', 'expired'] as const)(
    '%s 사용자는 보호 내용을 보지 않고 로그인으로 이동하며 원래 위치를 보존한다',
    async (status) => {
      authMock.status = status
      renderProtected()

      expect(screen.queryByText('보호된 내 기록')).toBeNull()
      expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
      expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe(
        '/records/analysis-42?view=trades#loss',
      )
    },
  )

  it('기존의 위험한 복귀 위치를 현재의 검증된 내 기록 위치로 교체한다', async () => {
    window.sessionStorage.setItem('thorp.auth.returnTarget', '//evil.example')
    renderProtected('/records/safe')

    await waitFor(() => {
      expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe('/records/safe')
    })
  })
})
