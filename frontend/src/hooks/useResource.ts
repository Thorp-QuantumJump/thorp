import { useCallback, useEffect, useState } from 'react'
import { ResultError } from '@/api/result'

/**
 * 열었을 때 받는 자원의 상태. 상세 패널·거래 내역·손실 구간 보기가 같은 모양으로 쓴다.
 *
 * <p>받지 못했다고 빈 값으로 바꾸지 않는다. 빈 표는 "기록이 없었다"는 거짓이 된다(원칙 VI).
 */
export type ResourceState<T> =
  /** `stale`은 다시 받는 동안 남겨 둔 앞선 응답이다. 페이지를 넘길 때 표와 버튼이 사라지지 않게 한다 */
  | { status: 'loading'; stale?: T }
  | { status: 'loaded'; data: T }
  | { status: 'failed'; message: string }

const FALLBACK_MESSAGE = '자료를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * `load`가 바뀔 때마다 부른다. 호출부는 `useCallback`으로 무엇을 부를지 고정해 넘긴다. 화면을 떠나면 요청을
 * 끊고, 떠난 뒤 온 응답은 버린다.
 *
 * <p>실패해도 스스로 다시 부르지 않는다. 사용자가 `retry`를 누를 때만 다시 부른다.
 */
export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
): ResourceState<T> & { retry: () => void } {
  const [state, setState] = useState<ResourceState<T>>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    // 앞선 응답이 있으면 남겨 둔다. 지우면 누른 버튼이 함께 사라져 키보드 초점을 잃는다(FR-022).
    setState((previous) =>
      previous.status === 'loaded' ? { status: 'loading', stale: previous.data } : { status: 'loading' },
    )
    load(controller.signal).then(
      (data) => {
        if (active) setState({ status: 'loaded', data })
      },
      (error: unknown) => {
        if (!active) return
        setState({
          status: 'failed',
          message: error instanceof ResultError ? error.message : FALLBACK_MESSAGE,
        })
      },
    )

    return () => {
      active = false
      controller.abort()
    }
  }, [load, attempt])

  return { ...state, retry }
}
