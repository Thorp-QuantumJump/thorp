import { useState } from 'react'
import { useAuth } from '@/auth/auth-context'
import type { AuthStateStatus, AuthenticatedUser } from '@/types/auth'

/**
 * 배우기 글 화면이 기준으로 삼는 로그인 상태. 확인 중(`checking`)과 `error`는 여기에 없다.
 *
 * - `pending`: 화면을 연 뒤 아직 한 번도 확인되지 않았다
 * - `anonymous`·`authenticated`: 마지막으로 확인된 상태. 로그인했으면 사용자까지 가린다
 * - `expired`·`logoutUnconfirmed`: 만료와 로그아웃 시작. 어떻게 보일지는 B12가 정한다(research R33 표)
 */
export type SettledAuth =
  | { kind: 'pending' }
  | { kind: 'anonymous' }
  | { kind: 'authenticated'; userId: number }
  | { kind: 'expired' }
  | { kind: 'logoutUnconfirmed' }

function settle(status: AuthStateStatus, user: AuthenticatedUser | null): SettledAuth | null {
  switch (status) {
    case 'checking':
    case 'error':
      return null
    case 'authenticated':
      return user ? { kind: 'authenticated', userId: user.userId } : null
    default:
      return { kind: status }
  }
}

function isSame(a: SettledAuth, b: SettledAuth): boolean {
  if (a.kind !== b.kind) return false
  return a.kind !== 'authenticated' || b.kind !== 'authenticated' || a.userId === b.userId
}

/**
 * 재확인을 로그인 상태 변경으로 읽지 않는 로그인 상태(013 research R33).
 *
 * <p><b>014는 탭이 다시 보일 때마다 상태를 `checking`으로 둔다.</b> 그때마다 글 끝을 내리면 비로그인으로
 * 답한 확인 문제가 탭을 오갈 때마다 처음으로 돌아간다. 그래서 확인 중과 `error` 동안에는 마지막으로 확인된
 * 상태를 그대로 쓰고 `concealed`만 켠다. 부르는 쪽은 그동안 부품을 내리지 않고 보이지 않게만 한다.
 *
 * <p>확인이 끝나 같은 상태(`anonymous`에서 `anonymous`, 같은 사용자의 `authenticated`)로 돌아오면 숨겼던
 * 것을 그대로 다시 보인다. 다른 상태가 되면 `settled`가 바뀌므로 부르는 쪽이 글 끝을 처음부터 그린다.
 */
export function useSettledAuth(): { settled: SettledAuth; concealed: boolean } {
  const { status, user } = useAuth()
  const current = settle(status, user)
  const [last, setLast] = useState<SettledAuth>(current ?? { kind: 'pending' })

  // 렌더 중에 앞 상태를 맞춘다(React 문서의 "props가 바뀔 때 상태 조정" 방식). 효과로 미루면 바뀐 상태와
  // 옛 상태가 한 번 섞여 그려진다.
  if (current && !isSame(current, last)) setLast(current)

  const settled = current ?? last
  return { settled, concealed: current === null && settled.kind !== 'pending' }
}
