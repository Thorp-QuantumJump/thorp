import { RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchSectorCatalog } from '@/api/sectors'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import { readSelectedSector, saveSelectedSector } from '@/marketflow/sectorLink'
import type { SectorSummary } from '@/types/sector'
import styles from './MarketFlow.module.css'

interface SectorSelectorProps {
  /** 지금 고른 섹터를 알린다. 고르지 않았으면 `null`이다. */
  onSelectionChange: (code: string | null) => void
}

type CatalogState =
  | { status: 'loading' }
  | { status: 'ready'; sectors: SectorSummary[] }
  /** 목록을 못 얻었다. **이 영역만 접고 분야 요약은 그대로 둔다.** */
  | { status: 'unavailable'; message: string; retryable: boolean }

/**
 * 섹터 선택은 **연결할 전략·대표 상품·뉴스의 범위만 좁힌다**(FR-016).
 *
 * 분야 요약·상세 요청에 섹터를 싣지 않는다. 계약에 그런 파라미터가 없고, 있어서도 안 된다.
 * 이 컴포넌트가 바깥으로 내보내는 것은 고른 코드 하나뿐이며 경제환경 자료를 읽지도 쓰지도
 * 않는다. 그렇게 경계를 그어야 SC-004가 테스트가 아니라 구조로 지켜진다.
 *
 * 목록은 002와 같은 `GET /sectors`를 쓰고 표시 순서도 002의 비교 함수를 그대로 쓴다. 같은
 * 목록을 두 화면이 다른 순서로 보이면 같은 목록이라는 사실이 사용자에게 사라진다.
 */
export function SectorSelector({ onSelectionChange }: SectorSelectorProps) {
  const [catalog, setCatalog] = useState<CatalogState>({ status: 'loading' })
  const [selected, setSelected] = useState<string | null>(null)
  const [droppedCode, setDroppedCode] = useState<string | null>(null)
  const [request, setRequest] = useState(0)

  // 부모가 인라인 함수를 넘겨도 목록을 다시 불러오지 않게 ref로 붙잡는다.
  // 이 값이 effect 의존성에 들어가면 렌더마다 재조회가 돌아 화면이 멈춘다.
  const notify = useRef(onSelectionChange)
  notify.current = onSelectionChange

  useEffect(() => {
    const controller = new AbortController()
    setCatalog({ status: 'loading' })

    fetchSectorCatalog(controller.signal).then((result) => {
      if (controller.signal.aborted) return

      if (result.status === 'loaded' || result.status === 'emptyCatalog') {
        const sectors = sortSectorsForDisplay(result.catalog.sectors)
        setCatalog({ status: 'ready', sectors })

        // 보관된 코드가 지금 목록에 있는지는 목록을 쥔 여기서만 볼 수 있다(sectorLink 주석).
        const stored = readSelectedSector()
        const usable = stored !== null && sectors.some((sector) => sector.code === stored)
        if (stored !== null && !usable) {
          // 조용히 버리지 않는다. 사용자는 자기가 고른 것이 사라진 이유를 들을 자격이 있다(원칙 VI).
          setDroppedCode(stored)
          saveSelectedSector(null)
        }
        setSelected(usable ? stored : null)
        notify.current(usable ? stored : null)
        return
      }

      setCatalog({
        status: 'unavailable',
        message: result.message,
        retryable: result.status === 'failed' && result.retryable,
      })
    })

    return () => controller.abort()
  }, [request])

  const choose = useCallback(
    (code: string | null) => {
      const next = selected === code ? null : code
      setDroppedCode(null)
      setSelected(next)
      saveSelectedSector(next)
      notify.current(next)
    },
    [selected],
  )

  if (catalog.status === 'loading') {
    return (
      <section className={styles.sectorPicker} aria-labelledby="sector-picker-title">
        <h3 id="sector-picker-title">섹터 좁혀 보기</h3>
        <p className={styles.sectorNote} role="status">섹터 목록을 불러오는 중입니다.</p>
      </section>
    )
  }

  if (catalog.status === 'unavailable') {
    return (
      <section className={styles.sectorPicker} aria-labelledby="sector-picker-title">
        <h3 id="sector-picker-title">섹터 좁혀 보기</h3>
        <p className={styles.sectorNote}>{catalog.message}</p>
        {catalog.retryable ? (
          <button type="button" className={styles.sectorRetry} onClick={() => setRequest((value) => value + 1)}>
            <RefreshCw size={14} aria-hidden="true" /> 목록 다시 불러오기
          </button>
        ) : null}
      </section>
    )
  }

  return (
    <section className={styles.sectorPicker} aria-labelledby="sector-picker-title">
      <h3 id="sector-picker-title">섹터 좁혀 보기</h3>
      <p className={styles.sectorNote}>
        섹터를 고르면 연결되는 전략·대표 상품·뉴스의 범위만 좁혀집니다. 위의 분야별 경제 흐름은 달라지지 않습니다.
      </p>
      {droppedCode !== null ? (
        <p className={styles.sectorNote} role="status">
          이전에 고른 섹터({droppedCode})가 지금 목록에 없어 선택이 풀렸습니다.
        </p>
      ) : null}
      {catalog.sectors.length === 0 ? (
        <p className={styles.sectorNote}>아직 고를 수 있는 섹터가 없습니다.</p>
      ) : (
        <div className={styles.sectorChips} role="group" aria-labelledby="sector-picker-title">
          {catalog.sectors.map((sector) => (
            <button
              key={sector.code}
              type="button"
              className={styles.sectorChip}
              aria-pressed={selected === sector.code}
              onClick={() => choose(sector.code)}
            >
              {sector.name}
            </button>
          ))}
        </div>
      )}
    </section>
  )
}
