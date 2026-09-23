import { useEffect } from 'react'

/** 열린 지표 카드만 전역 Escape를 받고, 닫히거나 화면을 떠나면 이벤트를 해제한다. */
export function useIndicatorEscape(expanded: boolean, onClose: () => void) {
  useEffect(() => {
    if (!expanded) return

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
      event.preventDefault()
      onClose()
    }

    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [expanded, onClose])
}
