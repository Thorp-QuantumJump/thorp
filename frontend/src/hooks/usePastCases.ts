import axios from 'axios'
import { useCallback, useEffect, useState } from 'react'
import { getRegimeCaseDetail, getRegimeSearch } from '@/api/regime'
import type { RegimeCaseDetail, RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'

/*
  시장 흐름이 010에서 읽는 두 가지(009 FR-019): 분야·종합 검색 결과와, 고른 사례 하나의 지표별 당시 경로.
  조회 실패는 그 자리에서만 다시 시도하며 분야 요약·상세를 막지 않는다.

  두 훅 모두 **받은 값을 그 값을 부른 키와 함께 둔다.** 키가 바뀐 직후의 렌더에는 이전 키의 값이 아직 남아 있는데,
  키가 다르면 '받는 중'으로 돌려준다. 그래야 분야를 바꾼 첫 렌더에 이전 분야의 사례가 새 분야 그래프에 겹치지 않는다.
  010 화면의 useRegimeSearch는 효과에서 결과를 비우므로 그 한 렌더 동안 이전 결과를 돌려준다.
*/

export type PastSearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  /** `message`는 서버가 실패 응답에 실은 문장이다(예: 종합 기준월이 아직 계산되지 않음). 없으면 null이다. */
  | { status: 'failed'; message: string | null }
  | { status: 'ready'; result: RegimeSearchResult }

/** 010 실패 응답 본문의 message. 010 화면의 LoadError와 같은 방식으로 읽는다. */
function serverMessage(error: unknown): string | null {
  const body: unknown = axios.isAxiosError(error) ? error.response?.data : null
  return body && typeof body === 'object' && 'isSuccess' in body && body.isSuccess === false &&
    'message' in body && typeof body.message === 'string' ? body.message : null
}

export type CaseDetailState =
  | { status: 'loading' }
  | { status: 'failed' }
  /** 응답이 요청한 사례와 다르다(010 FR-029). 섞어 보이지 않는다. */
  | { status: 'mismatch' }
  | { status: 'ready'; detail: RegimeCaseDetail }

/** 질의가 바뀔 때마다 다시 찾는다. 질의는 값으로 비교한다 — 부르는 쪽이 매번 새 객체를 넘겨도 다시 조회하지 않는다. */
export function usePastSearch(query: RegimeSearchQuery | null) {
  const key = query === null ? null : JSON.stringify(query)
  const [stored, setStored] = useState<{ key: string; value: PastSearchState } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  useEffect(() => {
    if (key === null) return
    const controller = new AbortController()
    setStored({ key, value: { status: 'loading' } })
    getRegimeSearch(JSON.parse(key) as RegimeSearchQuery, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setStored({ key, value: { status: 'ready', result } })
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) setStored({ key, value: { status: 'failed', message: serverMessage(caught) } })
      })
    return () => controller.abort()
  }, [key, attempt])

  const state: PastSearchState = key === null ? { status: 'idle' } : stored?.key === key ? stored.value : { status: 'loading' }
  return { state, retry }
}

/**
 * 고른 사례의 상세(지표별 당시 값과 공통 척도)를 읽는다. `endMonth`가 null이면('지금만') 조회하지 않는다.
 *
 * 같은 검색 안에서 사례를 오가도 다시 받지 않게 받은 상세를 기억한다. 공통 척도는 같은 검색의 모든 사례에 똑같이
 * 적용되므로(010 FR-028), 새 사례를 받는 동안이나 '지금만'일 때도 그 검색에서 마지막으로 받은 상세를
 * `scaleSource`로 돌려준다. 보통 범위 띠는 사례를 바꿔도 사라지지 않는다.
 */
export function useCaseDetail(searchId: string | null, endMonth: string | null) {
  const key = searchId !== null && endMonth !== null ? `${searchId}~${endMonth}` : null
  // 화면이 살아 있는 동안 쓰는 캐시다. 상태로 두되 고칠 때는 같은 Map에 더한다(다시 그릴 필요가 없다).
  const [cache] = useState(() => new Map<string, RegimeCaseDetail>())
  const [stored, setStored] = useState<{ key: string; value: CaseDetailState } | null>(null)
  const [latest, setLatest] = useState<RegimeCaseDetail | null>(null)
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  useEffect(() => {
    if (searchId === null || endMonth === null) return
    const current = `${searchId}~${endMonth}`
    const cached = cache.get(current)
    if (cached) {
      setStored({ key: current, value: { status: 'ready', detail: cached } })
      setLatest(cached)
      return
    }
    const controller = new AbortController()
    setStored({ key: current, value: { status: 'loading' } })
    getRegimeCaseDetail(endMonth, { searchId }, controller.signal)
      .then((detail) => {
        if (controller.signal.aborted) return
        // 다른 검색이나 다른 사례의 상세를 섞어 보이지 않는다(010 FR-029, CaseDetailLoader와 같은 검사).
        if (detail.searchId !== searchId || detail.endMonth !== endMonth) {
          setStored({ key: current, value: { status: 'mismatch' } })
          return
        }
        cache.set(current, detail)
        setStored({ key: current, value: { status: 'ready', detail } })
        setLatest(detail)
      })
      .catch(() => {
        if (!controller.signal.aborted) setStored({ key: current, value: { status: 'failed' } })
      })
    return () => controller.abort()
  }, [cache, searchId, endMonth, attempt])

  const state: CaseDetailState | null = key === null ? null : stored?.key === key ? stored.value : { status: 'loading' }
  const scaleSource = latest !== null && latest.searchId === searchId ? latest : null
  return { state, retry, scaleSource }
}
