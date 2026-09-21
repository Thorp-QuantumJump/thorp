import { useCallback } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { saveReturnTarget } from '@/auth/returnTarget'

/**
 * 익명 사용자를 로그인으로 보내고 **같은 주소로** 돌려보낸다(011 FR-025, 014 FR-011 방식).
 *
 * <p><b>주소를 인자로 넘기지 않는다.</b> 014의 `ProtectedRoute`와 같은 자리(`auth/returnTarget`)에
 * 지금 주소를 저장하고 `/login`으로 간다. 로그인 뒤에는 `AuthEntryGuard`가 그 값을 소비해 돌려보낸다.
 * 주소를 질의 인자로 실으면 복귀 위치가 두 벌이 되고, 외부에서 넣은 주소로 튕겨 보낼 길이 열린다.
 *
 * <p>011은 `ProtectedRoute` 밖에서 로그인을 요구하는 첫 화면이다(익명도 GET은 볼 수 있어 경로 전체를
 * 막을 수 없다). 그래서 이 동작을 훅 하나로 묶어 011의 여러 자리가 같은 길을 쓰게 한다.
 */
export function useLoginRedirect(): { redirectToLogin: () => void } {
  const navigate = useNavigate()
  const location = useLocation()
  const target = `${location.pathname}${location.search}${location.hash}`

  const redirectToLogin = useCallback(() => {
    saveReturnTarget(target)
    void navigate('/login', { replace: true })
  }, [navigate, target])

  return { redirectToLogin }
}
