import { useCallback, useEffect, useRef, useState } from 'react'
import { getRegimeSearch, getRegimeSearchById } from '@/api/regime'
import type { RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'

export interface RegimeSearchState {
  result: RegimeSearchResult | null
  error: unknown
  isLoading: boolean
  showMore: () => void
  retry: () => void
}

/**
 * 요청 순번과 취소를 함께 써서 늦은 응답이 현재 선택을 덮지 못하게 한다.
 *
 * <p>`savedSearchId`가 있으면 폼 질의 대신 그 식별자로 저장된 판을 고정 조회한다(006 research R17 — 내 기록의
 * '다시 보기'가 최신 revision 이 아니라 저장 당시 revision 을 열어야 "동일 버전 조회"가 성립한다). 폼 질의로
 * 새 검색을 열면 화면이 식별자를 지우고 이 훅은 원래 갈래로 돌아온다.
 */
export function useRegimeSearch(
  query: RegimeSearchQuery | null,
  savedSearchId: string | null = null,
): RegimeSearchState {
  const [result, setResult] = useState<RegimeSearchResult | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const [retryKey, setRetryKey] = useState(0)
  const sequence = useRef(0)
  const mode = query?.mode
  const field = query?.field
  const baselineMonth = query?.baselineMonth
  const queryKey = savedSearchId ?? `${mode ?? ''}|${field ?? ''}|${baselineMonth ?? ''}`
  const limit: 3 | 10 = expandedKey === queryKey ? 10 : 3

  const showMore = useCallback(() => setExpandedKey(queryKey), [queryKey])
  const retry = useCallback(() => setRetryKey((current) => current + 1), [])

  useEffect(() => {
    setResult(null)
    setError(null)
  }, [mode, field, baselineMonth, savedSearchId])

  useEffect(() => {
    const seq = ++sequence.current
    if (!mode && !savedSearchId) {
      setIsLoading(false)
      return
    }
    const controller = new AbortController()
    setIsLoading(true)
    setError(null)
    const request = savedSearchId
      ? getRegimeSearchById(savedSearchId, limit, controller.signal)
      : getRegimeSearch({ mode: mode!, field, baselineMonth, limit }, controller.signal)
    request
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
  }, [mode, field, baselineMonth, savedSearchId, limit, retryKey])

  return { result, error, isLoading, showMore, retry }
}
