import { useEffect, useRef, useState } from 'react'
import { type ProgressEvent, subscribeProgress } from '@/api/progressStream'
import { drainCount } from '@/components/result/progressPlayback'
import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * 실행 중 진행 곡선. 구독으로 받은 묶음을 거래일 단위로 풀어 그래프에 누적한다(spec 004 R-05).
 *
 * <p><b>받은 점만 그린다.</b> 중간값을 만들지 않고, 날짜가 앞서지 않는 점은 버린다. 이벤트가 재전달되면
 * 같은 점이 두 번 올 수 있는데(진행 저장 UPDATE 가 실패해 브로커가 다시 보내는 경우), 그대로 넣으면
 * 같은 날짜가 두 행이 되어 곡선이 겹친다.
 *
 * <p><b>실행이 끝나면 곡선을 지우지 않고 그대로 둔다.</b> 지우면 최종 시계열이 도착하기 전까지 그래프가
 * 비고, 사용자에게는 그려지던 것이 사라진 것으로 보인다. 최종 시계열을 받으면 화면이 그쪽으로 바꾼다.
 */
export type ProgressSeriesState = {
  /** 지금까지 공개한 점. 날짜 오름차순이다 */
  points: ResultTimeseriesPoint[]
  /** 마지막으로 받은 진행 보고의 거래일 수. 아직 없으면 `null` */
  progress: { completedTradingDays: number; totalTradingDays: number } | null
  /** 종결 이벤트를 받았는가 */
  finished: boolean
}

const empty: ProgressSeriesState = { points: [], progress: null, finished: false }

/**
 * @param token 공개 결과 토큰. `null`이면 구독하지 않는다
 * @param active 구독할 상태인가. 끝난 결과에 구독을 걸면 아무것도 오지 않는 연결이 남는다
 */
export function useProgressSeries(token: string | null, active: boolean): ProgressSeriesState {
  const [state, setState] = useState<ProgressSeriesState>(empty)

  // 아직 공개하지 않은 점. 화면 갱신마다 다시 만들지 않도록 ref 에 둔다.
  const queue = useRef<ResultTimeseriesPoint[]>([])
  // 마지막으로 공개한 날짜. 중복 판정 기준이다.
  const lastShown = useRef<string | null>(null)
  const finished = useRef(false)

  useEffect(() => {
    if (token === null || !active) return

    queue.current = []
    lastShown.current = null
    finished.current = false
    setState(empty)

    let frame = 0
    let running = true

    const enqueue = (event: ProgressEvent) => {
      for (const point of event.points) {
        const latestQueued = queue.current.at(-1)?.date ?? lastShown.current
        // 이미 공개했거나 대기열에 있는 날짜보다 앞서지 않으면 재전달된 점이다.
        if (latestQueued !== null && latestQueued !== undefined && point.date <= latestQueued) {
          continue
        }
        queue.current.push(point)
      }
      setState((current) => ({
        ...current,
        progress: {
          completedTradingDays: event.completedTradingDays,
          totalTradingDays: event.totalTradingDays,
        },
      }))
    }

    const step = () => {
      if (!running) return
      const count = drainCount(queue.current.length, finished.current)
      if (count > 0) {
        const revealed = queue.current.splice(0, count)
        lastShown.current = revealed.at(-1)?.date ?? lastShown.current
        setState((current) => ({ ...current, points: [...current.points, ...revealed] }))
      }
      frame = window.requestAnimationFrame(step)
    }

    const unsubscribe = subscribeProgress(token, {
      onProgress: enqueue,
      onFinished: () => {
        // 남은 대기열은 다음 프레임들이 한 번에 비운다. 여기서 비우면 곡선이 순간 이동한다.
        finished.current = true
        setState((current) => ({ ...current, finished: true }))
      },
      // 연결이 끊겨도 그린 곡선은 남긴다. `EventSource`가 스스로 다시 붙는다.
      onError: () => {},
    })

    frame = window.requestAnimationFrame(step)

    return () => {
      running = false
      window.cancelAnimationFrame(frame)
      unsubscribe()
    }
  }, [token, active])

  return state
}
