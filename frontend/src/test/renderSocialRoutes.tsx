import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthEntryGuard } from '@/auth/AuthEntryGuard'
import { AuthProvider } from '@/auth/AuthProvider'
import { DEFAULT_RETURN_TARGET } from '@/auth/returnTarget'
import { SocialAlreadyLinkedPage } from '@/pages/SocialAlreadyLinkedPage'
import { SocialCompletePage } from '@/pages/SocialCompletePage'
import { SocialEmailRequiredPage } from '@/pages/SocialEmailRequiredPage'
import { SocialLinkPage } from '@/pages/SocialLinkPage'
import { SocialSignupPage } from '@/pages/SocialSignupPage'
import { SocialVerifyEmailPage } from '@/pages/SocialVerifyEmailPage'
import type { PendingSocialView } from '@/types/auth'

/**
 * 소셜 화면을 앱과 같은 가드·라우트 짜임으로 띄운다. 가입·연결이 끝나 로그인 상태가 되면 가드가 복귀
 * 위치로 보내는 것까지 화면에서 확인할 수 있다.
 */
export function renderSocialRoutes(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <Routes>
          <Route path="/auth/social/complete" element={<SocialCompletePage />} />
          <Route
            path="/signup/social"
            element={
              <AuthEntryGuard>
                <SocialSignupPage />
              </AuthEntryGuard>
            }
          />
          <Route
            path="/auth/social/verify-email"
            element={
              <AuthEntryGuard>
                <SocialVerifyEmailPage />
              </AuthEntryGuard>
            }
          />
          <Route
            path="/auth/social/link"
            element={
              <AuthEntryGuard>
                <SocialLinkPage />
              </AuthEntryGuard>
            }
          />
          <Route
            path="/auth/social/email-required"
            element={
              <AuthEntryGuard>
                <SocialEmailRequiredPage />
              </AuthEntryGuard>
            }
          />
          <Route
            path="/auth/social/already-linked"
            element={
              <AuthEntryGuard>
                <SocialAlreadyLinkedPage />
              </AuthEntryGuard>
            }
          />
          <Route path="/login" element={<h1>로그인 화면</h1>} />
          <Route path="/signup" element={<h1>회원가입 화면</h1>} />
          <Route path={DEFAULT_RETURN_TARGET} element={<h1>기본 도착 화면</h1>} />
          <Route path="/records/*" element={<h1>내 기록 화면</h1>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

export function pendingView(overrides: Partial<PendingSocialView> = {}): PendingSocialView {
  return {
    provider: 'kakao',
    stage: 'SIGNUP',
    email: 'member@example.com',
    suggestedNickname: '카카오닉',
    linkReason: null,
    linkTargetMaskedEmail: null,
    linkMethods: [],
    expiresAt: '2026-10-01T03:10:00Z',
    ...overrides,
  }
}
