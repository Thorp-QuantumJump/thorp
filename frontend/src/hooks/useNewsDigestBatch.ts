import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import type { NewsDigestResult } from '@/types/marketNews'

/*
  뉴스 구역이 한꺼번에 여는 조회 여럿(012 FR-001)을 관리한다. 주요 뉴스 하나, 고른 섹터마다 하나, 분류마다 하나다.

  - **같은 키는 다시 받지 않는다.** 사용자가 투자 대상을 하나 더 고르면 그 섹터의 조회 하나만 새로 나간다. 키는 섹터
    목록과 받을 묶음 수로 만든다.
  - 조회 하나가 실패해도 나머지는 그대로다. 실패한 키만 다시 시도한다. 다시 받는 동안은 `retrying`이라 화면이 그 칸만
    받는 중으로 보이고, 다른 칸은 흔들리지 않는다.
  - `enabled`가 거짓이면 아무것도 받지 않는다. 화면 아래쪽 구역이라 가까이 왔을 때 받는다.
*/

export type NewsRequest = { key: string; sectorIds: readonly string[]; limit: number }

export type NewsSettled = { status: 'ready'; result: NewsDigestResult } | { status: 'failed' } | { status: 'retrying' }

/** 섹터 목록과 묶음 수로 조회 키를 만든다. 같은 조회는 같은 키다. */
export function newsRequestKey(sectorIds: readonly string[], limit: number): string {
  return `${sectorIds.join(',') || '*'}#${limit}`
}

export function useNewsDigestBatch(requests: readonly NewsRequest[], enabled: boolean) {
  const [settled, setSettled] = useState<Readonly<Record<string, NewsSettled>>>({})
  const inflight = useRef(new Map<string, AbortController>())

  useEffect(() => {
    if (!enabled) return
    for (const request of requests) {
      const current = settled[request.key]
      if ((current && current.status !== 'retrying') || inflight.current.has(request.key)) continue
      const controller = new AbortController()
      inflight.current.set(request.key, controller)
      fetchMarketNewsDigests(request.sectorIds, controller.signal, undefined, request.limit)
        .then((result) => {
          if (!controller.signal.aborted) setSettled((current) => ({ ...current, [request.key]: { status: 'ready', result } }))
        })
        .catch(() => {
          if (!controller.signal.aborted) setSettled((current) => ({ ...current, [request.key]: { status: 'failed' } }))
        })
        .finally(() => {
          if (inflight.current.get(request.key) === controller) inflight.current.delete(request.key)
        })
    }
  }, [requests, settled, enabled])

  // 화면을 떠나면 받는 중인 조회를 모두 끊는다.
  useEffect(() => {
    const running = inflight.current
    return () => running.forEach((controller) => controller.abort())
  }, [])

  const retry = useCallback((key: string) => {
    setSettled((current) => ({ ...current, [key]: { status: 'retrying' } }))
  }, [])

  return { settled, retry }
}
