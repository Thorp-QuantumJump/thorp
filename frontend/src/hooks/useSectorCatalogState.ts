import { useEffect, useState } from 'react'
import { fetchSectorCatalog } from '@/api/sectors'
import type { SectorCatalog } from '@/types/sector'

export type SectorCatalogState =
  | { status: 'loading' }
  | { status: 'loaded'; catalog: SectorCatalog }
  /** `retry`가 없으면 다시 눌러도 달라지지 않는 실패다 */
  | { status: 'unavailable'; message: string; retry?: () => void }

const CATALOG_FAILED = '섹터 목록을 불러오지 못해 섹터 이름과 대표 상품 정보를 보일 수 없습니다.'
const CATALOG_NOT_READY = '섹터 목록이 아직 준비 중이라 섹터 이름과 대표 상품 정보를 보일 수 없습니다.'

/**
 * 결과 상세가 섹터 이름과 대표 상품을 찾는 001 섹터 목록. 상세를 열 때 받는다 — 결과 첫 화면은 부르지 않는다
 * (SC-001).
 *
 * <p>받지 못하면 이름 대신 코드를 쓰고(`buildSectorLabels`), 대표 상품 정보는 열지 않는다. 다시 눌러 달라질 수
 * 있는 상태(대응표 미발행·빈 목록·다시 시도할 수 있는 실패)에만 `retry`를 준다(001 research 결정 6).
 */
export function useSectorCatalogState(): SectorCatalogState {
  const [state, setState] = useState<SectorCatalogState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    const reload = () => setAttempt((count) => count + 1)

    setState({ status: 'loading' })
    void fetchSectorCatalog(controller.signal).then((result) => {
      if (!active) return
      if (result.status === 'loaded') {
        setState({ status: 'loaded', catalog: result.catalog })
        return
      }
      // 대응표 미발행과 빈 목록은 파이프라인이 채우면 달라진다. 001 화면과 같이 다시 불러오기를 준다.
      // 빈 목록을 목록으로 넘기면 모든 섹터가 "목록에 없는 섹터"로 보여 섹터가 빠진 것처럼 읽힌다.
      if (result.status === 'mapNotReady' || result.status === 'emptyCatalog') {
        setState({ status: 'unavailable', message: CATALOG_NOT_READY, retry: reload })
        return
      }
      setState({
        status: 'unavailable',
        message: CATALOG_FAILED,
        retry: result.retryable ? reload : undefined,
      })
    })

    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  return state
}
