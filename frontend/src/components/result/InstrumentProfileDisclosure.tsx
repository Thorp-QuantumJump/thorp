import { useId, useRef, useState } from 'react'
import { InstrumentProfilePanel } from '@/components/diagnosis/InstrumentProfilePanel'
import { Button } from '@/components/ui/button'
import type { SectorCatalogState } from '@/hooks/useSectorCatalogState'
import { findMatchingSector } from './sectorLabels'
import type { ResultConfigItem } from '@/types/result'
import type { SectorCatalog } from '@/types/sector'
import styles from './resultDetail.module.css'

/**
 * 결과의 섹터 후보 집합에 든 **모든** 섹터의 대표 상품 정보를 상세 패널 안에서 펼친다(FR-007, R-10).
 *
 * <p>펼친 내용은 001의 `InstrumentProfilePanel`이 그린다. 구성종목·기준 지수·한계 문구·출처는 001이 소유하고
 * 005는 다시 쓰지 않는다. 이 컴포넌트는 섹터마다 접어 두고 연 것만 조회하는 일만 한다. 실제로 보유했는지와
 * 무관하게 후보 전부를 둔다.
 *
 * <p><b>지금 대응표의 대표 ETF가 결과가 쓴 종목과 다르면 펼치지 않는다.</b> 상품 정보 조회는 섹터 코드로 지금의
 * 대표 ETF를 돌려준다. 결과를 낸 뒤 대응표가 바뀌었으면 다른 상품을 이 결과의 근거처럼 보이게 된다.
 */

type InstrumentProfileDisclosureProps = {
  items: readonly ResultConfigItem[]
  catalog: SectorCatalogState
}

export function InstrumentProfileDisclosure({ items, catalog }: InstrumentProfileDisclosureProps) {
  if (catalog.status === 'loading') {
    return (
      <p className={styles.copy} role="status">
        섹터 목록을 확인하는 중입니다.
      </p>
    )
  }

  if (catalog.status === 'unavailable') {
    return (
      <div className={styles.row} role="alert">
        <p className={styles.alert}>{catalog.message}</p>
        {catalog.retry ? (
          <Button type="button" variant="outline" size="sm" onClick={catalog.retry}>
            다시 불러오기
          </Button>
        ) : null}
      </div>
    )
  }

  return (
    <ul className={`${styles.profileList} ${styles.profile}`}>
      {items.map((item) => (
        <ProfileItem key={item.sectorId} item={item} catalog={catalog.catalog} />
      ))}
    </ul>
  )
}

function ProfileItem({ item, catalog }: { item: ResultConfigItem; catalog: SectorCatalog }) {
  const [open, setOpen] = useState(false)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()

  const sector = catalog.sectors.find((candidate) => candidate.code === item.sectorId)
  const matched = findMatchingSector(catalog, item)

  if (!sector) {
    return (
      <li>
        <p className={styles.subTitle}>{item.sectorId}</p>
        <p className={styles.muted}>
          지금 섹터 목록에 없는 섹터라 대표 상품 정보를 볼 수 없습니다. ({item.instrumentId})
        </p>
      </li>
    )
  }

  if (!matched) {
    return (
      <li>
        <p className={styles.subTitle}>{sector.name}</p>
        <p className={styles.muted}>
          이 결과가 쓴 상품({item.instrumentId})과 지금의 대표 ETF({sector.representativeEtf.name})가 달라
          상품 정보를 보이지 않습니다. 지금 상품의 정보로 이 결과를 설명할 수 없습니다.
        </p>
      </li>
    )
  }

  const label = `${sector.name} · ${matched.representativeEtf.name}`

  return (
    <li
      onKeyDown={(event) => {
        // 펼친 안쪽에서 Esc로 닫고 초점을 버튼으로 돌려준다. 바깥 상세 패널까지 닫히지 않게 여기서 멈춘다.
        if (event.key !== 'Escape' || !open) return
        event.stopPropagation()
        setOpen(false)
        toggleRef.current?.focus()
      }}
    >
      <Button
        ref={toggleRef}
        type="button"
        variant="link"
        size="sm"
        // 섹터·상품 이름이 길어도 좁은 화면에서 줄을 바꾼다.
        className="h-auto px-0 text-left whitespace-normal"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        {label}
      </Button>
      {open ? <InstrumentProfilePanel sectorCode={item.sectorId} id={panelId} /> : null}
    </li>
  )
}
