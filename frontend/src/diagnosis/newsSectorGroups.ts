import type { NewsSector } from '@/types/marketNews'

/*
  뉴스 칩을 한 번 더 묶는 화면 분류다(012 FR-005 보강). 섹터가 18개라 한 줄에 다 펼치면 칩 벽이 된다.

  - **분류는 화면이 가진다.** 섹터 목록과 이름은 여전히 `GET /market-news/sectors`가 주고, 여기에는
    식별자를 어느 분류에 둘지만 적는다. 섹터 이름을 상수로 두지 않는다는 012의 규칙은 그대로다.
  - 배정표에 없는 섹터가 목록에 새로 오면 '기타'에 넣는다. 섹터가 늘었다고 칩이 사라지지 않는다.
  - 응답에 섹터가 하나도 없는 분류는 보이지 않는다. 누를 것이 없는 탭을 두지 않는다.
  - 분류 탭은 칩을 펼치는 자리일 뿐 뉴스를 좁히지 않는다. 칩으로 좁혀 보는 조회는 섹터 하나다.
  - 뉴스 구역의 '분류별 뉴스'(012 FR-001 ③)는 분류에 배정된 섹터를 **한 번의 조회로** 묻고 012가 합친 결과를 쓴다
    (2026-10-02). 섹터마다 따로 불러 화면이 합치면 중복 제거와 상위 묶음 선정을 화면이 다시 하게 되어 012의 판정과 갈라진다.
*/

export type NewsSectorGroup = {
  id: string
  name: string
  sectors: NewsSector[]
}

const GROUP_ASSIGNMENT: ReadonlyArray<{ id: string; name: string; sectorIds: readonly string[] }> = [
  { id: 'tech', name: '기술', sectorIds: ['SEMICONDUCTOR', 'SECONDARY_BATTERY', 'NEWS_IT', 'NEWS_MEDIA'] },
  {
    id: 'industry',
    name: '제조·산업',
    sectorIds: ['CONSTRUCTION', 'AUTO', 'HEAVY_INDUSTRY', 'NEWS_MATERIALS', 'NEWS_ENERGY', 'NEWS_TRANSPORT'],
  },
  { id: 'finance', name: '금융', sectorIds: ['BANK', 'SECURITIES', 'NEWS_FINANCE'] },
  { id: 'consumer', name: '소비·헬스케어', sectorIds: ['COSMETICS', 'NEWS_CONSUMER', 'NEWS_HEALTHCARE'] },
  { id: 'market', name: '시장·경제', sectorIds: ['NEWS_MARKET', 'NEWS_MACRO'] },
]

const OTHER_GROUP = { id: 'other', name: '기타' }

/** 받은 섹터를 분류별로 나눈다. 분류 안의 순서는 받은 목록의 순서(012 등록표의 화면 순서)를 따른다. */
export function groupNewsSectors(sectors: readonly NewsSector[]): NewsSectorGroup[] {
  const groupOf = new Map<string, string>()
  for (const group of GROUP_ASSIGNMENT) {
    for (const sectorId of group.sectorIds) groupOf.set(sectorId, group.id)
  }

  const groups = [...GROUP_ASSIGNMENT, OTHER_GROUP].map((group) => ({
    id: group.id,
    name: group.name,
    sectors: sectors.filter((sector) => (groupOf.get(sector.sectorId) ?? OTHER_GROUP.id) === group.id),
  }))
  return groups.filter((group) => group.sectors.length > 0)
}
