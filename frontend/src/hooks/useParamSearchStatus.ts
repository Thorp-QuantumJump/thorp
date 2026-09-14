import { useCallback, useEffect, useState } from 'react'
import { ParamSearchError, getParamSearch } from '@/api/paramSearch'
import type { ParamSearchDetail, ParamSearchStatus } from '@/types/paramSearch'

/** 더 폴링하지 않아도 되는 상태. 재시도로 다시 열리면 `refetch`가 폴링을 되살린다. */
const finishedStatuses: ParamSearchStatus[] = ['COMPLETED', 'PARTIAL', 'FAILED', 'REJECTED']

const defaultIntervalMs = 3_000

export type ParamSearchStatusState = {
  detail: ParamSearchDetail | null
  error: string | null
  isLoading: boolean
  /** 지금 폴링 중인가. 끝난 묶음이면 `false`다. */
  isPolling: boolean
  refetch: () => void
}

/**
 * 묶음 상태 폴링(`GET /param-searches/{searchId}`).
 *
 * <p>접수는 202로 끝나고 진행은 이 조회로만 보인다. <b>실패해도 200</b>이므로 `status`로 판단하고,
 * 끝난 상태에 닿으면 폴링을 멈춘다 — 끝난 묶음을 계속 두드릴 이유가 없다.
 *
 * <p>타이머는 겹치지 않도록 응답을 받은 뒤에 다음 것을 건다. 화면을 떠나면 해제하고, 떠난 뒤 도착한
 * 응답은 버린다 — 늦은 응답이 다음 묶음의 상태를 덮어쓰면 화면이 남의 진행을 보이게 된다.
 *
 * <p>조회 실패는 폴링을 끊지 않는다. 다만 <b>없는 묶음(404)은 다시 물어도 생기지 않으므로</b> 멈춘다.
 */
export function useParamSearchStatus(
  searchId: string | null,
  options: { intervalMs?: number } = {},
): ParamSearchStatusState {
  const intervalMs = options.intervalMs ?? defaultIntervalMs
  const [detail, setDetail] = useState<ParamSearchDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [isPolling, setIsPolling] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  const refetch = useCallback(() => {
    setReloadToken((current) => current + 1)
  }, [])

  useEffect(() => {
    if (!searchId) {
      setDetail(null)
      setError(null)
      setIsLoading(false)
      setIsPolling(false)
      return
    }

    let active = true
    let timer = 0

    const stop = () => {
      if (active) setIsPolling(false)
    }

    const tick = async () => {
      try {
        const next = await getParamSearch(searchId)
        if (!active) return

        setDetail(next)
        setError(null)
        setIsLoading(false)

        if (finishedStatuses.includes(next.status)) {
          stop()
          return
        }
      } catch (caught) {
        if (!active) return

        const failure =
          caught instanceof ParamSearchError
            ? caught
            : new ParamSearchError('진행 상태를 불러오지 못했습니다.')
        setError(failure.message)
        setIsLoading(false)

        if (failure.status === 404) {
          stop()
          return
        }
      }

      if (active) timer = window.setTimeout(tick, intervalMs)
    }

    setIsLoading(true)
    setIsPolling(true)
    void tick()

    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [searchId, intervalMs, reloadToken])

  return { detail, error, isLoading, isPolling, refetch }
}
