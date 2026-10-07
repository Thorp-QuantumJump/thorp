import { useCallback, useEffect, useState } from 'react'
import { fetchMarketFlowField } from '@/api/marketFlow'
import type { FieldDetailResult, MarketFlowFieldId } from '@/types/marketFlow'

export type FieldDetailLoad =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; data: FieldDetailResult }

/**
 * 여러 분야의 상세를 한꺼번에 받는다. 종합 비교가 일곱 분야의 지표 그래프를 함께 그릴 때 쓴다(009 FR-019, 2026-10-07).
 *
 * 분야마다 따로 받으므로 한 분야가 실패해도 나머지는 그려진다. 받은 값은 그 값을 부른 목록과 함께 두어, 목록이
 * 바뀐 첫 렌더에 이전 목록의 상세가 보이지 않는다. 다시 시도는 목록 전체를 다시 받는다 — 실패는 드물고 분야는 일곱이다.
 */
export function useFieldDetails(fieldIds: readonly MarketFlowFieldId[]) {
  const key = fieldIds.join(',')
  const [stored, setStored] = useState<{ key: string; loads: Record<string, FieldDetailLoad> } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  useEffect(() => {
    const ids = key === '' ? [] : (key.split(',') as MarketFlowFieldId[])
    const controller = new AbortController()
    setStored({ key, loads: Object.fromEntries(ids.map((id) => [id, { status: 'loading' as const }])) })
    for (const id of ids) {
      fetchMarketFlowField(id, controller.signal)
        .then((data) => {
          if (!controller.signal.aborted) setStored((current) => current && current.key === key ? { key, loads: { ...current.loads, [id]: { status: 'ready', data } } } : current)
        })
        .catch(() => {
          if (!controller.signal.aborted) setStored((current) => current && current.key === key ? { key, loads: { ...current.loads, [id]: { status: 'error' } } } : current)
        })
    }
    return () => controller.abort()
  }, [key, attempt])

  const loads: Record<string, FieldDetailLoad> = stored?.key === key ? stored.loads : {}
  return { loads, retry }
}
