import { useCallback, useEffect, useState } from 'react'
import { ResultError, fetchResult, fetchResultTimeseries } from '@/api/result'
import { RESULT_POLL_INTERVAL_MS } from '@/components/result/constants'
import type { PublicResultDetail, ResultStatus, ResultTimeseries } from '@/types/result'

/**
 * 시계열을 받지 못한 이유. 그래프 자리가 이 값으로 안내한다(B04).
 *
 * `code`는 서버가 준 이름 그대로다. 받지 못했다고 빈 계열로 바꾸지 않는다 — 빈 곡선은 "돌았고 비어
 * 있었다"는 거짓이 된다(004 §2.5).
 */
export type TimeseriesState =
  | { status: 'none' }
  | { status: 'loaded'; data: ResultTimeseries }
  | { status: 'unavailable'; code: string | null; message: string }

export type ResultState =
  | { status: 'loading' }
  | { status: 'ready'; detail: PublicResultDetail; timeseries: TimeseriesState }
  /** `410 RESULT_EXPIRED`. 결과를 열지 않는다(SC-008) */
  | { status: 'expired' }
  /** `404`·`400`. 잘못된 주소이며 만료와 다르게 안내한다 */
  | { status: 'notFound' }
  /** 연결 실패 등. 사용자가 다시 시도할 수 있다 */
  | { status: 'error'; message: string }

const inFlightStatuses: ResultStatus[] = ['QUEUED', 'RUNNING']

/**
 * 볼 결과가 있는 상태인가. 시작 전에 취소된 결과는 하위 자원이 `409 NO_PARTIAL_RESULT`라 부르지 않는다.
 * 실패한 결과도 `409 BACKTEST_FAILED`라 부르지 않는다.
 */
function hasViewableResult(detail: PublicResultDetail): boolean {
  if (detail.status === 'COMPLETED') return true
  return detail.status === 'CANCELLED' && detail.summaryUnavailable !== 'CANCELLED_BEFORE_START'
}

function toFailureState(error: unknown): ResultState {
  const failure = error instanceof ResultError ? error : new ResultError('결과를 불러오지 못했습니다.')
  if (failure.is('RESULT_EXPIRED')) return { status: 'expired' }
  if (failure.is('RESOURCE_NOT_FOUND') || failure.is('INVALID_INPUT')) return { status: 'notFound' }
  return { status: 'error', message: failure.message }
}

/**
 * 공개 토큰으로 결과를 읽는다(`GET /results/{token}` → 종결 상태면 `/timeseries`).
 *
 * <p><b>첫 화면이 부르는 요청은 상세와 시계열 둘뿐이다(SC-001).</b> 거래 내역·비중·예외는 사용자가
 * '자세히 보기'를 열 때 따로 부른다.
 *
 * <p>`QUEUED`·`RUNNING`이면 응답을 받은 뒤에 다음 조회를 건다 — 타이머가 겹치지 않는다. 화면을 떠나면
 * 요청을 취소하고, 떠난 뒤 도착한 응답은 버린다.
 *
 * <p>만료·없음은 다시 물어도 바뀌지 않으므로 멈춘다. 연결 실패도 폴링을 멈추고 사용자가 `retry`를
 * 누를 때 다시 부른다 — 조용히 계속 두드리지 않는다.
 */
export function useResult(token: string): ResultState & { retry: () => void } {
  const [state, setState] = useState<ResultState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    let timer = 0

    const load = async () => {
      let detail: PublicResultDetail
      try {
        detail = await fetchResult(token, controller.signal)
      } catch (error) {
        if (active) setState(toFailureState(error))
        return
      }
      if (!active) return

      if (inFlightStatuses.includes(detail.status)) {
        setState({ status: 'ready', detail, timeseries: { status: 'none' } })
        timer = window.setTimeout(() => void load(), RESULT_POLL_INTERVAL_MS)
        return
      }

      if (!hasViewableResult(detail)) {
        setState({ status: 'ready', detail, timeseries: { status: 'none' } })
        return
      }

      let timeseries: TimeseriesState
      try {
        timeseries = {
          status: 'loaded',
          data: await fetchResultTimeseries(token, controller.signal),
        }
      } catch (error) {
        const failure =
          error instanceof ResultError ? error : new ResultError('그래프 자료를 불러오지 못했습니다.')
        // 상세를 받은 뒤 만료 시각을 넘긴 경우다. 이미 받은 숫자를 남기지 않고 만료로 닫는다(SC-008).
        if (failure.is('RESULT_EXPIRED')) {
          if (active) setState({ status: 'expired' })
          return
        }
        timeseries = { status: 'unavailable', code: failure.code, message: failure.message }
      }
      if (active) setState({ status: 'ready', detail, timeseries })
    }

    setState({ status: 'loading' })
    void load()

    return () => {
      active = false
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [token, attempt])

  return { ...state, retry }
}
