import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import type { AuthenticatedUser } from '@/types/auth'
import { AccountProfilePage } from './AccountProfilePage'

const previewUser: AuthenticatedUser = {
  userId: 0,
  email: 'preview@example.com',
  nickname: '미리보기 사용자',
  status: 'ACTIVE',
}

const previewAuth: AuthContextValue = {
  status: 'authenticated',
  user: previewUser,
  login: async () => previewUser,
  logout: async () => {},
  recheck: async () => ({ status: 'authenticated', user: previewUser }),
  // 화면이 미리보기에서는 부르지 않는다. 불려도 서버에 가지 않고 값만 돌려준다.
  updateNickname: async (nickname) => ({ ...previewUser, nickname }),
  completeSignIn: () => undefined,
}

/** 개발 서버에서만 라우트에 연결한다. 서버 인증이나 저장 요청은 하지 않는다. */
export function AccountProfilePreviewPage() {
  return (
    <AuthContext.Provider value={previewAuth}>
      <AccountProfilePage preview />
    </AuthContext.Provider>
  )
}
