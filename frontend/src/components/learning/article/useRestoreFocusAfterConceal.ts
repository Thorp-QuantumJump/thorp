import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react'

/**
 * 로그인 상태를 다시 확인하는 동안 숨겼던 자리의 초점을 확인이 끝난 뒤 되돌린다(013 research R33, FR-019).
 *
 * <p><b>숨기면 초점이 빠진다.</b> 글 끝은 확인하는 동안 `visibility: hidden`으로 자리만 남기는데, 숨긴 요소에
 * 있던 초점은 브라우저가 문서 몸통으로 옮긴다. 014가 탭이 다시 보일 때마다 확인하므로, 키보드·화면 낭독기
 * 사용자가 확인 문제의 풀이나 로그인 연결에 둔 초점을 탭을 오갈 때마다 잃는다. 탭을 떠날 때 빠진 초점도
 * 브라우저가 돌아올 때 되돌리려다 숨김에 막힌다.
 *
 * <p><b>사용자가 옮긴 초점은 되돌리지 않는다.</b> 자리 밖으로 초점이 옮겨 가거나 사용자가 다른 곳을 누르면
 * 기억을 지운다. 확인이 끝났을 때 초점이 이미 다른 요소에 있어도 건드리지 않는다.
 */
export function useRestoreFocusAfterConceal(
  region: RefObject<HTMLElement | null>,
  concealed: boolean,
): void {
  const lastFocused = useRef<HTMLElement | null>(null)
  const wasConcealed = useRef(concealed)

  useEffect(() => {
    const handleFocusIn = (event: FocusEvent) => {
      const root = region.current
      const target = event.target
      lastFocused.current =
        root && target instanceof HTMLElement && root.contains(target) ? target : null
    }
    const handlePointerDown = (event: PointerEvent) => {
      const last = lastFocused.current
      if (last && !(event.target instanceof Node && last.contains(event.target))) {
        lastFocused.current = null
      }
    }
    document.addEventListener('focusin', handleFocusIn)
    document.addEventListener('pointerdown', handlePointerDown)
    return () => {
      document.removeEventListener('focusin', handleFocusIn)
      document.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [region])

  // 숨김이 풀린 커밋 직후 되돌린다. 이 시점에는 자리가 이미 보이므로 초점을 받을 수 있다.
  useLayoutEffect(() => {
    const reopened = wasConcealed.current && !concealed
    wasConcealed.current = concealed
    if (!reopened) return
    const target = lastFocused.current
    if (!target || !target.isConnected) return
    const active = document.activeElement
    if (active && active !== document.body) return
    target.focus({ preventScroll: true })
  }, [concealed])
}
