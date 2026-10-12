import { useEffect, useState, type RefObject } from 'react'

/**
 * 대상이 지금 화면 밖에 있는가. 화면에 들어오고 나갈 때마다 바뀐다(`useNearViewport`는 한 번 참이 되면 그대로다).
 *
 * <p>잴 수 없으면(대상이 없거나 `IntersectionObserver`가 없는 환경) 화면 안에 있는 것으로 본다. 대상이 다시 그려져
 * 바뀔 수 있으면 `watchKey`를 바꿔 다시 잰다.
 */
export function useOffScreen(
  targetRef: RefObject<HTMLElement | null>,
  watchKey?: unknown,
): boolean {
  const [offScreen, setOffScreen] = useState(false)
  useEffect(() => {
    const target = targetRef.current
    setOffScreen(false)
    if (!target || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) setOffScreen(!entry.isIntersecting)
    })
    observer.observe(target)
    return () => observer.disconnect()
  }, [targetRef, watchKey])
  return offScreen
}
