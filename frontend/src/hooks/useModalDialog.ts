import { useEffect, type RefObject } from 'react'

/**
 * 네이티브 `<dialog>`를 `open` 값에 맞춰 모달로 열고 닫는다.
 *
 * <p>`showModal()`이라야 top layer로 올라가 배경이 잠기고, 초점 가두기·`Esc` 닫기를 브라우저가 해 준다. `open` 속성으로
 * 열면 제자리에 그냥 그려져 메뉴 같은 것과 겹친다. jsdom에는 `showModal()`·`close()`가 없어 그때만 `open` 속성으로
 * 대신한다. 닫힐 때 무엇을 할지(초점 되돌리기 등)는 부른 쪽이 정한다.
 */
export function useModalDialog(ref: RefObject<HTMLDialogElement | null>, open: boolean): void {
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal()
      else dialog.setAttribute('open', '')
    }
    if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
    }
  }, [ref, open])
}
