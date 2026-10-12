import { useEffect, useState } from 'react'

/**
 * 새 배지·레벨의 조용한 알림(013 FR-030, design-notes §6). 처음부터 비어 있는 `role="status"` 영역에 글자만 넣는다.
 * 영역을 글자와 함께 한꺼번에 만들면 화면 낭독기가 읽지 않는 경우가 많아 잠깐 기다렸다가 넣는다. 초점을 옮기지 않는다.
 */
export function QuietNotice({ text }: { text: string }) {
  const [shown, setShown] = useState('')
  useEffect(() => {
    setShown('')
    if (!text) return
    const timer = setTimeout(() => setShown(text), 250)
    return () => clearTimeout(timer)
  }, [text])
  return (
    <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {shown}
    </p>
  )
}
