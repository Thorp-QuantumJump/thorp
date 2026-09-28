import { useCallback, useEffect, useState } from 'react'
import { fetchInstrumentProfile, type InstrumentProfileResult } from '@/api/sectors'

/**
 * 대표 상품 정보를 한 번 불러 그 상태를 돌려준다.
 *
 * **조회를 화면 조각에서 떼어 놓는다.** 펼친 카드는 같은 응답을 설명·사실·보유 종목 세 자리에
 * 나눠 그리는데, 그리는 조각마다 스스로 부르면 한 상품을 보는 동안 같은 왕복이 세 번 일어난다.
 *
 * `enabled`가 false인 동안에는 부르지 않는다. 접어 둔 카드는 상품 정보를 쓰지 않으므로, 목록을
 * 훑기만 하는 사용자가 그 왕복을 치르지 않게 한다.
 *
 * `null`은 아직 결과가 없는 상태, 곧 불러오는 중이다. 화면이 쓰는 표현과 같다.
 */
export type InstrumentProfileState = {
  result: InstrumentProfileResult | null
  reload: () => void
}

export function useInstrumentProfile(
  sectorCode: string,
  enabled = true,
): InstrumentProfileState {
  const [result, setResult] = useState<InstrumentProfileResult | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    if (!enabled) return

    const controller = new AbortController()
    let isCurrent = true

    setResult(null)
    void fetchInstrumentProfile(sectorCode, controller.signal).then((next) => {
      if (isCurrent) setResult(next)
    })

    return () => {
      isCurrent = false
      controller.abort()
    }
  }, [enabled, sectorCode, reloadCount])

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  return { result, reload }
}
