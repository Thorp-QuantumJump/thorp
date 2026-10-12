import { useEffect, useRef, useState } from 'react'

/**
 * 새 배지·레벨의 조용한 알림(013 FR-030, design-notes §6). 처음부터 비어 있는 `role="status"` 영역에 글자만 넣는다.
 * 영역을 글자와 함께 한꺼번에 만들면 화면 낭독기가 읽지 않는 경우가 많아 잠깐 기다렸다가 넣는다. 초점을 옮기지 않는다.
 *
 * <p>`held`인 동안(로그인 상태를 다시 확인하는 동안, research R33)은 글자를 넣지 않고 미뤄 둔다. 다시 보이게 되면 아직
 * 넣지 못한 글자만 한 번 넣는다. 이미 넣은 글자는 다시 넣지 않으므로 탭을 오갈 때마다 같은 알림이 되풀이되지 않는다.
 */
export function QuietNotice({ text, held = false }: { text: string; held?: boolean }) {
  const [shown, setShown] = useState('')
  // 아직 영역에 넣지 못한 글자가 있는가.
  const pending = useRef(false)
  useEffect(() => {
    setShown('')
    pending.current = text !== ''
  }, [text])
  useEffect(() => {
    if (held || !pending.current) return
    // 글자를 넣는 순간에 넣은 것으로 적는다. 기다리는 사이 확인이 시작되면 타이머만 지워지고 다음에 다시 넣는다.
    const timer = setTimeout(() => {
      pending.current = false
      setShown(text)
    }, 250)
    return () => clearTimeout(timer)
  }, [text, held])
  return (
    <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {shown}
    </p>
  )
}
