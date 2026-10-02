import type { SectorClassification, SectorSummary } from '@/types/sector'

/**
 * 섹터의 표시 순서를 화면이 결정론적으로 정한다(research.md 결정 2).
 *
 * **서버 응답의 배열 순서에 기대지 않는다.** 001 계약이 `sectors` 배열의 정렬을 정하지 않아,
 * 응답 순서에 기대면 백엔드 구현이 바뀔 때 화면 규칙이 조용히 따라 바뀐다.
 *
 * 이 순서는 목록 표시만의 것이 아니다. M3의 동일 비중 초깃값이 나눠떨어지지 않을 때 남는
 * 잔여분을 **표시 순서상 첫 번째** 섹터에 몰아주므로, 같은 섹터 집합이면 사용자가 어떤 순서로
 * 골랐든 언제나 같은 순서가 나와야 한다.
 */

/** 업종을 테마보다 먼저 둔다. FR-003이 요구한 업종/테마 구분을 목록 자체로 드러내기 위해서다. */
const classificationRank: Record<SectorClassification, number> = {
  INDUSTRY: 0,
  THEME: 1,
}

/**
 * 섹터 코드(영문) 정렬보다 사용자가 항목을 찾기 쉽다.
 *
 * 옵션을 모두 적어 둔 까닭은 구현마다 다른 기본값을 물려받지 않기 위해서다. 정렬 순서를 M3의
 * 잔여분 자리가 그대로 쓰므로 실행 환경에 따라 순서가 달라지면 비중 계산까지 따라 달라진다.
 *
 * **남는 위험**: 옵션을 고정해도 로케일 자료(ICU)를 갖추지 않은 런타임에서는 `ko` 대조가
 * 요청한 대로 동작하지 않을 수 있다. 순서 규칙 자체는 research.md 결정 2가 소유하므로 여기서
 * 뒤집지 않고, `sectorOrder.test.ts`가 대표 이름 집합의 기대 순서를 고정해 그런 환경에서
 * 조용히 달라지는 대신 검사가 실패하게 한다.
 */
const koreanNameCollator = new Intl.Collator('ko', {
  usage: 'sort',
  sensitivity: 'variant',
  numeric: false,
  caseFirst: 'false',
  ignorePunctuation: false,
})

/** 1차 키는 업종/테마 구분, 2차 키는 이름의 한국어 정렬이다. */
export function compareSectorsForDisplay(left: SectorSummary, right: SectorSummary): number {
  const byClassification =
    classificationRank[left.classification] - classificationRank[right.classification]
  if (byClassification !== 0) return byClassification

  const byName = koreanNameCollator.compare(left.name, right.name)
  if (byName !== 0) return byName

  // 이름이 같은 섹터가 둘 이상이어도 순서가 흔들리지 않게 코드로 마지막 자리를 고정한다.
  return left.code < right.code ? -1 : left.code > right.code ? 1 : 0
}

/** 받은 배열을 건드리지 않고 표시 순서로 정렬한 새 배열을 돌려준다. */
export function sortSectorsForDisplay(sectors: readonly SectorSummary[]): SectorSummary[] {
  return [...sectors].sort(compareSectorsForDisplay)
}

/**
 * '지금 국내 시장' 띠의 ETF 순서. 국내 시장을 대표하는 반도체를 맨 앞에 두고, 나머지는 받은 순서(표시 순서)를
 * 따른다(002 FR-018, 2026-10-02 사용자 결정). 목록의 표시 순서와 M3의 잔여분 자리는 바꾸지 않는다.
 */
export const MARKET_STRIP_LEAD_SECTOR_CODE = 'SEMICONDUCTOR'

export function orderSectorsForMarketStrip(sectors: readonly SectorSummary[]): SectorSummary[] {
  const lead = sectors.filter((sector) => sector.code === MARKET_STRIP_LEAD_SECTOR_CODE)
  return [...lead, ...sectors.filter((sector) => sector.code !== MARKET_STRIP_LEAD_SECTOR_CODE)]
}
