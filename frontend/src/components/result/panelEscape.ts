import type { KeyboardEvent } from 'react'

/**
 * 결과 근처에서 연 보기를 Esc로 닫는 처리(FR-022).
 *
 * <p><b>입력 칸에서 누른 Esc는 그 칸의 것이다.</b> 달력을 닫거나 고르던 값을 되돌리려고 누른 Esc가 패널까지
 * 올라오면, 채우던 거래 내역 조건이 통째로 사라진다. 그래서 이벤트가 입력 요소에서 났으면 패널은 닫지 않는다.
 *
 * <p>닫을 때는 위로 전파하지 않는다. 보기가 겹쳐 있어도 Esc 한 번에 하나만 닫는다.
 */
export function closePanelOnEscape(onClose: () => void) {
  return (event: KeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Escape') return
    if ((event.target as HTMLElement | null)?.closest('input, select, textarea')) return
    event.stopPropagation()
    onClose()
  }
}
