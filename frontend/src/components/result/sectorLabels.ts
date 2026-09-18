import type { ResultConfigItem } from '@/types/result'
import type { SectorCatalog } from '@/types/sector'

/**
 * 상세 패널이 섹터와 종목을 부르는 이름.
 *
 * <p>결과에는 섹터 코드와 종목 식별자만 있다. 이름은 001의 섹터 목록(`GET /sectors`)에서 읽는데, 그 목록은
 * **지금의** 대응표다. 결과를 낼 때의 대표 ETF와 지금의 대표 ETF가 다르면 지금 ETF의 이름을 붙이지 않고
 * 결과가 쓴 종목 식별자를 그대로 둔다. 목록을 받지 못하면 코드를 그대로 쓴다 — 이름을 지어내지 않는다.
 */
export type SectorLabels = {
  sector: (code: string) => string
  instrument: (instrumentId: string) => string
}

/** 결과가 쓴 종목과 지금 대응표의 대표 ETF가 같은 섹터를 찾는다. 다르거나 없으면 `null`이다. */
export function findMatchingSector(catalog: SectorCatalog | null, item: ResultConfigItem) {
  const sector = catalog?.sectors.find((candidate) => candidate.code === item.sectorId)
  if (!sector || sector.representativeEtf.instrumentId !== item.instrumentId) return null
  return sector
}

export function buildSectorLabels(
  items: readonly ResultConfigItem[],
  catalog: SectorCatalog | null,
): SectorLabels {
  const sector = (code: string) =>
    catalog?.sectors.find((candidate) => candidate.code === code)?.name ?? code

  const instrument = (instrumentId: string) => {
    const item = items.find((candidate) => candidate.instrumentId === instrumentId)
    if (!item) return instrumentId
    const matched = findMatchingSector(catalog, item)
    return `${sector(item.sectorId)} · ${matched ? matched.representativeEtf.name : instrumentId}`
  }

  return { sector, instrument }
}
