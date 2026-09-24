import { useEffect, useState } from 'react'
import { fetchResultIndexComparison, normalizeResultError } from '@/api/result'
import type { ResultIndexComparison } from '@/types/result'

export type IndexReferenceState =
  | { status: 'off' }
  | { status: 'loading' }
  | { status: 'ready'; data: ResultIndexComparison }
  | { status: 'error'; message: string; retry: () => void }

/**
 * 참고 지수선(KOSPI, FR-017). 켜졌을 때만 서버가 환산한 계열을 받는다 — 꺼져 있으면 요청이 없다(SC-001).
 * 화면은 받은 값을 다시 계산하지 않는다.
 */
export function useIndexReference(token: string, enabled: boolean): IndexReferenceState {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<IndexReferenceState>({ status: 'off' })

  useEffect(() => {
    if (!enabled) {
      setState({ status: 'off' })
      return
    }
    const controller = new AbortController()
    setState({ status: 'loading' })
    void fetchResultIndexComparison(token, controller.signal)
      .then((data) => setState({ status: 'ready', data }))
      .catch((error) => {
        if (controller.signal.aborted) return
        setState({
          status: 'error',
          message: normalizeResultError(error).message,
          retry: () => setAttempt((value) => value + 1),
        })
      })
    return () => controller.abort()
  }, [token, enabled, attempt])

  return state
}
