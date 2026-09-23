import { useCallback, useEffect, useState } from 'react'
import { fetchPriceSeries, type PriceSeriesResult } from '@/api/sectors'

/**
 * 대표 ETF의 가격 흐름을 한 번 불러 그 상태를 돌려준다.
 *
 * 조회를 화면 조각에서 떼어 놓는 까닭은 `useInstrumentProfile`과 같다. 보고 있는 대상이 바뀌면 그
 * 대상의 값을 새로 부르고, 앞선 요청은 취소한다 — 취소하지 않으면 늦게 도착한 응답이 지금 보고
 * 있는 다른 상품의 선을 덮는다.
 *
 * **카드를 접어 두어도 부른다.** 접은 카드가 보여 주는 것이 바로 이 선이라, 펼칠 때까지 미루면
 * 가장 먼저 보이는 자리가 비어 있게 된다. 상품 정보와 다른 점이 이것이다.
 *
 * `null`은 아직 결과가 없는 상태, 곧 불러오는 중이다.
 */
export type InstrumentPriceSeriesState = {
  result: PriceSeriesResult | null
  reload: () => void
}

export function useInstrumentPriceSeries(sectorCode: string): InstrumentPriceSeriesState {
  const [result, setResult] = useState<PriceSeriesResult | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let isCurrent = true

    setResult(null)
    void fetchPriceSeries(sectorCode, controller.signal).then((next) => {
      if (isCurrent) setResult(next)
    })

    return () => {
      isCurrent = false
      controller.abort()
    }
  }, [sectorCode, reloadCount])

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  return { result, reload }
}
