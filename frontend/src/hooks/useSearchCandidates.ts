import { useCallback, useEffect, useRef, useState } from 'react'
import { ParamSearchError, getParamSearchCandidates } from '@/api/paramSearch'
import type { CandidateQuery } from '@/api/paramSearch'
import type { CandidatePage } from '@/types/paramSearch'

export type SearchCandidatesState = {
  page: CandidatePage | null
  error: string | null
  isLoading: boolean
  refetch: () => void
}

/**
 * 후보 목록 조회(`GET /param-searches/{searchId}/candidates`).
 *
 * <p>질의가 바뀌면 다시 부른다. <b>늦게 온 응답이 최신 것을 덮지 않도록</b> 순번으로 막는다 —
 * 정렬을 빠르게 두 번 바꾸면 먼저 보낸 요청이 나중에 도착할 수 있고, 그러면 화면의 정렬 표시와
 * 실제 목록이 어긋난다.
 *
 * <p>질의 객체는 매 렌더마다 새로 만들어져도 된다. 내용이 같으면 다시 부르지 않는다.
 */
export function useSearchCandidates(
  searchId: string | null,
  query: CandidateQuery = {},
): SearchCandidatesState {
  const [page, setPage] = useState<CandidatePage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)
  const requestSeq = useRef(0)

  const queryKey = JSON.stringify(query)

  const refetch = useCallback(() => {
    setReloadToken((current) => current + 1)
  }, [])

  useEffect(() => {
    if (!searchId) {
      setPage(null)
      setError(null)
      setIsLoading(false)
      return
    }

    const seq = ++requestSeq.current
    let active = true
    setIsLoading(true)

    getParamSearchCandidates(searchId, JSON.parse(queryKey) as CandidateQuery)
      .then((next) => {
        if (!active || seq !== requestSeq.current) return
        setPage(next)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (!active || seq !== requestSeq.current) return
        setError(
          caught instanceof ParamSearchError
            ? caught.message
            : '후보 목록을 불러오지 못했습니다.',
        )
      })
      .finally(() => {
        if (active && seq === requestSeq.current) setIsLoading(false)
      })

    return () => {
      active = false
    }
  }, [searchId, queryKey, reloadToken])

  return { page, error, isLoading, refetch }
}
