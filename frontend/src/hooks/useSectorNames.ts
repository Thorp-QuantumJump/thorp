import { useCallback, useEffect, useState } from 'react'
import { fetchSectorCatalog } from '@/api/sectors'

/**
 * 종목 ID를 사용자가 고른 섹터 이름으로 읽어 준다.
 *
 * <p><b>이름의 주인은 002의 섹터 목록이다.</b> 인계 계약이 "003이 필요로 하는 섹터 이름·대표 ETF
 * 이름은 `GET /sectors`로 직접 받는다"고 정해 두었으므로(`useSectorSelection` 참고), 이름표를
 * 화면에 상수로 두거나 인계 페이로드에 실어 나르지 않고 목록에서 찾는다. 대응표 버전이 바뀌면
 * 어느 쪽이 기준인지 흐려지는 것을 그 계약이 막는다.
 *
 * <p><b>못 찾으면 ID를 그대로 보인다.</b> `069500.XKRX`는 읽히지 않지만, 이름을 지어내는 것보다는
 * 낫다 — 사용자가 고른 적 없는 섹터 이름이 대상으로 보이면 그 자체가 틀린 정보다.
 *
 * <p>목록을 받지 못해도 알리지 않는다. 이 훅이 채우는 것은 이름표뿐이고, 화면이 하려는 일(탐색
 * 대상 확인)은 ID만으로도 이어진다. 불러오기 실패를 여기서 경고로 띄우면 할 수 있는 일을 막는다.
 */
export function useSectorNames(): (instrumentId: string) => string {
  const [names, setNames] = useState<ReadonlyMap<string, string>>(new Map())

  useEffect(() => {
    const controller = new AbortController()

    void fetchSectorCatalog(controller.signal).then((result) => {
      if (controller.signal.aborted) return
      if (result.status !== 'loaded' && result.status !== 'emptyCatalog') return

      setNames(
        new Map(
          result.catalog.sectors.map((sector) => [sector.representativeEtf.instrumentId, sector.name]),
        ),
      )
    })

    return () => controller.abort()
  }, [])

  return useCallback((instrumentId: string) => names.get(instrumentId) ?? instrumentId, [names])
}
