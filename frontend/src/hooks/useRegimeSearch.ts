import { useCallback, useEffect, useRef, useState } from 'react'
import { getRegimeSearch } from '@/api/regime'
import type { RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'

export interface RegimeSearchState {
  result: RegimeSearchResult | null
  error: unknown
  isLoading: boolean
  showMore: () => void
  retry: () => void
}

/** 요청 순번과 취소를 함께 써서 늦은 응답이 현재 선택을 덮지 못하게 한다. */
export function useRegimeSearch(query: RegimeSearchQuery | null): RegimeSearchState {
  const [result, setResult] = useState<RegimeSearchResult | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [limit, setLimit] = useState<3 | 10>(3)
  const [retryKey, setRetryKey] = useState(0)
  const sequence = useRef(0)
  const mode = query?.mode
  const field = query?.field
  const baselineMonth = query?.baselineMonth

  const showMore = useCallback(() => setLimit(10), [])
  const retry = useCallback(() => setRetryKey((current) => current + 1), [])

  useEffect(() => {
    setLimit(3)
    setResult(null)
    setError(null)
  }, [mode, field, baselineMonth])

  useEffect(() => {
    const seq = ++sequence.current
    if (!mode) {
      setIsLoading(false)
      return
    }
    const controller = new AbortController()
    setIsLoading(true)
    setError(null)
    getRegimeSearch({ mode, field, baselineMonth, limit }, controller.signal)
      .then((next) => {
        if (seq !== sequence.current) return
        setResult(next)
      })
      .catch((caught: unknown) => {
        if (seq !== sequence.current || controller.signal.aborted) return
        setResult(null)
        setError(caught)
      })
      .finally(() => {
        if (seq === sequence.current) setIsLoading(false)
      })

    return () => controller.abort()
  }, [mode, field, baselineMonth, limit, retryKey])

  return { result, error, isLoading, showMore, retry }
}
