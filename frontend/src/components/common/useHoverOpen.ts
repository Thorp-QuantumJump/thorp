import { useCallback, useEffect, useRef, useState } from 'react'

/** 마우스가 지나가기만 할 때 열리지 않도록 두는 틈 */
export const HOVER_OPEN_DELAY_MS = 80
/** 트리거에서 말풍선으로 마우스를 옮기는 동안 닫히지 않도록 두는 틈 */
export const HOVER_CLOSE_DELAY_MS = 80

/**
 * 얹어서 여는 말풍선의 열림 상태. 여닫는 주체를 하나로 둔다.
 *
 * <p>지연은 우리가 걸고 <b>상태가 바뀔 때마다 반드시 지운다</b> — 닫으라는 신호가 오면 예약된 열기가 남아
 * 있을 수 없다. HoverCard 처럼 자기 타이머를 가진 부품은 초점 직후 Esc 를 누르면 닫힌 뒤 저절로 다시
 * 열렸다(S15P21E205-422). 얹기는 {@link schedule}로 틈을 두고, 초점·누르기·Esc 는 {@link settle}로 곧바로 정한다.
 */
export function useHoverOpen() {
  const [open, setOpen] = useState(false)
  const timerRef = useRef<number | undefined>(undefined)

  const clearPending = useCallback(() => {
    if (timerRef.current !== undefined) {
      window.clearTimeout(timerRef.current)
      timerRef.current = undefined
    }
  }, [])

  /** 예약된 것을 지우고 곧바로 정한다. 닫기 신호가 예약된 열기를 반드시 이긴다. */
  const settle = useCallback(
    (next: boolean) => {
      clearPending()
      setOpen(next)
    },
    [clearPending],
  )

  const schedule = useCallback(
    (next: boolean, delay: number) => {
      clearPending()
      timerRef.current = window.setTimeout(() => {
        timerRef.current = undefined
        setOpen(next)
      }, delay)
    },
    [clearPending],
  )

  // 화면을 떠난 뒤 타이머가 깨어나 없는 컴포넌트를 건드리지 않게 한다.
  useEffect(() => clearPending, [clearPending])

  return { open, settle, schedule }
}
