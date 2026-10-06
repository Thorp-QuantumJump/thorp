import { useEffect, useState, type RefObject } from 'react'

/**
 * 요소가 화면 가까이(기본 600px 앞) 왔는지. 한 번 참이 되면 다시 거짓이 되지 않는다.
 *
 * 화면 아래쪽에 있어 처음에는 보이지 않는 구역이 조회를 늦게 시작하게 할 때 쓴다(투자 대상 고르기의 뉴스 구역).
 * `IntersectionObserver`가 없는 환경(오래된 브라우저·시험 환경)에서는 바로 참이다 — 늦추는 것은 아낄 뿐이고, 못 받는
 * 것보다 일찍 받는 편이 낫다.
 */
export function useNearViewport(ref: RefObject<Element | null>, rootMargin = '600px'): boolean {
  const supported = typeof window !== 'undefined' && 'IntersectionObserver' in window
  const [near, setNear] = useState(!supported)

  useEffect(() => {
    if (near || !supported || !ref.current) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setNear(true)
          observer.disconnect()
        }
      },
      { rootMargin },
    )
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [near, supported, ref, rootMargin])

  return near
}
