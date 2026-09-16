import type { SectorClassification, SectorSummary } from '@/types/sector'

/** 목록에 보이는 업종/테마 구분 이름이다. 화면 표시 전용이라 이 파일 밖으로 내보내지 않는다. */
const classificationLabels: Record<SectorClassification, string> = {
  INDUSTRY: '업종',
  THEME: '테마',
}

type SectorListItemProps = {
  sector: SectorSummary
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

/**
 * 섹터 목록의 한 항목이다. 섹터 이름·업종/테마 구분·대표 ETF 이름을 함께 보인다(FR-003).
 *
 * **이 시점에는 표시만 한다.** 섹터를 고르는 체크박스는 M3이, 대표 상품 정보 펼침은 M5가 더한다.
 *
 * 계산식·자료 공급원·거리 함수·실험 식별자·서버 설정을 보이지 않는다(FR-011). 그래서 응답이
 * 함께 주는 `instrumentId`·`benchmarkIndex`·`listedOn`을 여기서 그리지 않는다. 상장일은 M4가
 * 안내 문장을 만드는 데 쓰고 화면에 날것으로 늘어놓지 않는다.
 */
export function SectorListItem({ sector, className }: SectorListItemProps) {
  return (
    <li className={className} data-sector-code={sector.code}>
      <span data-slot="heading">
        <strong data-slot="name">{sector.name}</strong>
        <span data-slot="classification">{classificationLabels[sector.classification]}</span>
      </span>
      <span data-slot="etf">
        <span data-slot="etf-label">대표 ETF</span>
        <span data-slot="etf-name">{sector.representativeEtf.name}</span>
      </span>
    </li>
  )
}
