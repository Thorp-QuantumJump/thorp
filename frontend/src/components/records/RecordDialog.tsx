import { type ReactNode, useEffect, useRef } from 'react'
import styles from './records.module.css'

type Props = {
  open: boolean
  /** 닫을 때 부른다. 초점은 연 버튼으로 되돌린다 — 그 일은 부른 쪽이 `returnFocusTo`로 맡긴다 */
  onClose: () => void
  labelledBy: string
  children: ReactNode
}

/**
 * 네이티브 `<dialog>` 껍데기(spec 006 FR-021).
 *
 * <p><b>라이브러리를 더하지 않는다.</b> `showModal()`이 초점 가두기·`Esc` 닫기·배경 비활성을 브라우저에서 해 준다. 직접
 * 만들면 그 셋을 각각 다시 구현하게 되고, 하나만 빠져도 키보드 사용자가 갇힌다.
 *
 * <p>닫힘은 한 곳으로 모은다 — `Esc`와 취소 버튼이 모두 `close` 이벤트를 지나므로, 부른 쪽은 어떻게 닫혔는지 가리지 않고
 * 정리할 수 있다.
 */
export function RecordDialog({ open, onClose, labelledBy, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    // jsdom 에는 showModal 이 없다(테스트 환경). 그때는 open 속성만으로 내용이 보이면 된다.
    if (open && !dialog.open) dialog.showModal?.()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      open={open || undefined}
      aria-labelledby={labelledBy}
      className={styles.dialog}
      onClose={onClose}
      onCancel={onClose}
    >
      {children}
    </dialog>
  )
}
