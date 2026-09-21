import { useCallback, useEffect, useState } from 'react'
import { fetchMyRecords } from '@/api/myRecords'
import { ApiClientError } from '@/api/client'
import type { MyRecordListItem, MyRecordListQuery } from '@/types/myRecords'

/**
 * 목록 상태(spec 006 US1).
 *
 * <p>받지 못했다고 빈 목록으로 바꾸지 않는다 — 빈 목록은 "기록이 없다"는 거짓이 된다(원칙 VI). 첫 쪽을
 * 받지 못하면 `failed`, 다음 쪽을 받지 못하면 받아 둔 항목은 남기고 `moreError`만 채운다.
 */
export type MyRecordsState =
  | { status: 'loading' }
  | { status: 'failed'; message: string }
  | {
      status: 'loaded'
      items: MyRecordListItem[]
      /** 다음 쪽이 없으면 null */
      nextCursor: string | null
      loadingMore: boolean
      moreError: string | null
    }

const FALLBACK = '기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

function messageOf(error: unknown): string {
  return error instanceof ApiClientError ? error.message : FALLBACK
}

/**
 * 질의가 바뀌면 첫 쪽부터 다시 받는다. `loadMore`는 이전 응답의 `nextCursor`로 다음 쪽을 받아 뒤에 붙인다 —
 * offset 이 아니라 커서라 사이에 새 항목이 들어와도 겹치거나 빠지지 않는다(research R14).
 *
 * <p>화면을 떠나면 요청을 끊고, 떠난 뒤 온 응답은 버린다. 실패해도 스스로 다시 부르지 않는다.
 */
export function useMyRecords(query: MyRecordListQuery) {
  const [state, setState] = useState<MyRecordsState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [more, setMore] = useState<{ cursor: string; attempt: number } | null>(null)

  // 질의 객체는 화면이 매 렌더마다 새로 만들 수 있다. 내용이 같으면 다시 부르지 않는다.
  const queryKey = JSON.stringify(query)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    setMore(null)
    fetchMyRecords(JSON.parse(queryKey) as MyRecordListQuery, controller.signal).then(
      (page) => {
        if (!active) return
        setState({
          status: 'loaded',
          items: page.items,
          nextCursor: page.nextCursor,
          loadingMore: false,
          moreError: null,
        })
      },
      (error: unknown) => {
        if (active) setState({ status: 'failed', message: messageOf(error) })
      },
    )
    return () => {
      active = false
      controller.abort()
    }
  }, [queryKey, attempt])

  useEffect(() => {
    if (!more) return
    const controller = new AbortController()
    let active = true
    setState((previous) =>
      previous.status === 'loaded' ? { ...previous, loadingMore: true, moreError: null } : previous,
    )
    fetchMyRecords(
      { ...(JSON.parse(queryKey) as MyRecordListQuery), cursor: more.cursor },
      controller.signal,
    ).then(
      (page) => {
        if (!active) return
        setState((previous) =>
          previous.status === 'loaded'
            ? {
                status: 'loaded',
                items: [...previous.items, ...page.items],
                nextCursor: page.nextCursor,
                loadingMore: false,
                moreError: null,
              }
            : previous,
        )
      },
      (error: unknown) => {
        if (!active) return
        setState((previous) =>
          previous.status === 'loaded'
            ? { ...previous, loadingMore: false, moreError: messageOf(error) }
            : previous,
        )
      },
    )
    return () => {
      active = false
      controller.abort()
    }
  }, [more, queryKey])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])
  const loadMore = useCallback(() => {
    setState((previous) => {
      if (previous.status === 'loaded' && previous.nextCursor && !previous.loadingMore) {
        setMore({ cursor: previous.nextCursor, attempt: Date.now() })
      }
      return previous
    })
  }, [])

  return { ...state, retry, loadMore }
}
