import { useEffect, useState } from 'react'
import { fetchSectorCatalog } from '@/api/sectors'
import type { SectorCatalog } from '@/types/sector'

export type SectorCatalogState =
  | { status: 'loading' }
  | { status: 'loaded'; catalog: SectorCatalog }
  /** `retry`가 없으면 다시 눌러도 달라지지 않는 실패다 */
  | { status: 'unavailable'; message: string; retry?: () => void }

const CATALOG_FAILED = '섹터 목록을 불러오지 못해 섹터 이름과 대표 상품 정보를 보일 수 없습니다.'
const CATALOG_NOT_READY = '섹터 목록이 아직 준비되지 않아 섹터 이름과 대표 상품 정보를 보일 수 없습니다.'

/**
 * 결과 상세가 섹터 이름과 대표 상품을 찾는 001 섹터 목록. 상세를 열 때 받는다 — 결과 첫 화면은 부르지 않는다
 * (SC-001).
 *
 * <p>받지 못하면 이름 대신 코드를 쓰고(`buildSectorLabels`), 대표 상품 정보는 열지 않는다. 다시 눌러 달라질 수
 * 있는 실패에만 `retry`를 준다(001 research 결정 6).
 */
export function useSectorCatalogState(): SectorCatalogState {
  const [state, setState] = useState<SectorCatalogState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    setState({ status: 'loading' })
    void fetchSectorCatalog(controller.signal).then((result) => {
      if (!active) return
      if (result.status === 'loaded' || result.status === 'emptyCatalog') {
        setState({ status: 'loaded', catalog: result.catalog })
        return
      }
      if (result.status === 'mapNotReady') {
        setState({ status: 'unavailable', message: CATALOG_NOT_READY })
        return
      }
      setState({
        status: 'unavailable',
        message: CATALOG_FAILED,
        retry: result.retryable ? () => setAttempt((count) => count + 1) : undefined,
      })
    })

    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  return state
}
