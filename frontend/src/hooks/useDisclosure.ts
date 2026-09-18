import { useCallback, useRef, useState } from 'react'

/**
 * 결과 근처에서 여닫는 보기(자세히 보기, 손실이 커진 때 보기)의 상태.
 *
 * <p>여는 것은 `button` 하나다 — Enter와 Space가 그대로 동작한다(FR-022). 닫으면 초점을 여는 버튼으로
 * 돌려준다. 돌려주지 않으면 키보드만 쓰는 사용자가 화면 처음부터 다시 찾아 내려와야 한다.
 *
 * <p>주소를 바꾸지 않는다. 결과 화면을 떠나지 않고 연다(SC-006).
 */
export function useDisclosure(panelId: string) {
  const [open, setOpen] = useState(false)
  const toggleRef = useRef<HTMLButtonElement>(null)

  const close = useCallback(() => {
    setOpen(false)
    toggleRef.current?.focus()
  }, [])

  return {
    open,
    close,
    toggleProps: {
      ref: toggleRef,
      'aria-expanded': open,
      // 접혀 있으면 패널이 없으므로 가리키지 않는다. 없는 id를 가리키면 관계를 버리는 보조 기술이 있다.
      'aria-controls': open ? panelId : undefined,
      onClick: () => setOpen((current) => !current),
    },
  }
}
