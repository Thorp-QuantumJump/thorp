import { useEffect, useState } from 'react'

/** 미디어 쿼리가 지금 맞는지 따라간다. jsdom 처럼 `matchMedia`가 없으면 맞지 않는 것으로 본다. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia?.(query).matches ?? false)
  useEffect(() => {
    const list = window.matchMedia?.(query)
    if (!list) return undefined
    const update = () => setMatches(list.matches)
    update()
    list.addEventListener('change', update)
    return () => list.removeEventListener('change', update)
  }, [query])
  return matches
}
