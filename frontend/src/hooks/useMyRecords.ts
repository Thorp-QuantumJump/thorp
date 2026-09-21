import { useCallback, useEffect, useMemo, useState } from 'react'
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
 *
 * <p>질의 객체는 화면이 매 렌더마다 새로 만들 수 있어 내용으로 견준다. 키 순서가 다르면 같은 질의도 다른
 * 문자열이라 다시 부르지만, 필터 컴포넌트가 한 객체를 펼쳐 고치므로 순서는 유지된다.
 */
export function useMyRecords(query: MyRecordListQuery) {
  const [state, setState] = useState<MyRecordsState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [moreCursor, setMoreCursor] = useState<string | null>(null)

  const queryKey = JSON.stringify(query)
  const stableQuery = useMemo(() => JSON.parse(queryKey) as MyRecordListQuery, [queryKey])

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    setMoreCursor(null)
    fetchMyRecords(stableQuery, controller.signal).then(
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
  }, [stableQuery, attempt])

  useEffect(() => {
    if (!moreCursor) return
    const controller = new AbortController()
    let active = true
    setState((previous) =>
      previous.status === 'loaded' ? { ...previous, loadingMore: true, moreError: null } : previous,
    )
    fetchMyRecords({ ...stableQuery, cursor: moreCursor }, controller.signal).then(
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
        // 커서를 놓아 준다. 같은 커서를 다시 넣으면 React 가 갱신을 건너뛰어 effect 가 돌지 않고, 사용자는
        // 눌러도 아무 일이 없는 '더 보기'를 본다.
        setMoreCursor(null)
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
  }, [moreCursor, stableQuery])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])
  // 연달아 눌러도 한 번만 받는 것은 loadingMore 검사가 막는다.
  const loadMore = useCallback(() => {
    if (state.status === 'loaded' && state.nextCursor && !state.loadingMore) {
      setMoreCursor(state.nextCursor)
    }
  }, [state])

  /**
   * 한 줄만 제자리에서 고친다(이름 변경). 목록을 다시 받지 않는 이유는 그 사이 새 항목이 끼면 사용자가 보던 자리가
   * 움직이기 때문이다 — 바뀐 것은 이름 하나뿐이다.
   */
  const replaceItem = useCallback((recordId: string, name: string) => {
    setState((previous) =>
      previous.status === 'loaded'
        ? {
            ...previous,
            items: previous.items.map((item) =>
              item.recordId === recordId ? { ...item, name } : item,
            ),
          }
        : previous,
    )
  }, [])

  /** 지운 줄을 없앤다. 되돌리는 경로가 없으므로 되돌릴 값을 들고 있지 않는다(research R6). */
  const removeItem = useCallback((recordId: string) => {
    setState((previous) =>
      previous.status === 'loaded'
        ? { ...previous, items: previous.items.filter((item) => item.recordId !== recordId) }
        : previous,
    )
  }, [])

  return { ...state, retry, loadMore, replaceItem, removeItem }
}
