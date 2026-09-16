import { useId } from 'react'
import { WeightInput } from './WeightInput'
import type { SectorClassification, SectorSummary } from '@/types/sector'

/** 목록에 보이는 업종/테마 구분 이름이다. 화면 표시 전용이라 이 파일 밖으로 내보내지 않는다. */
const classificationLabels: Record<SectorClassification, string> = {
  INDUSTRY: '업종',
  THEME: '테마',
}

/** 고른 섹터의 비중을 그 자리에서 다룬다. 선택되지 않은 항목에는 주지 않는다. */
export type SectorWeightControl = {
  /** 0.1%p 단위 정수다. */
  valueTenths: number
  onCommit: (weightTenths: number) => void
}

type SectorListItemProps = {
  sector: SectorSummary
  selected: boolean
  onSelectedChange: (selected: boolean) => void
  /** 고른 섹터에만 준다. 주면 같은 항목 안에 비중 입력 칸이 나타난다(FR-005). */
  weight?: SectorWeightControl
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

/**
 * 섹터 목록의 한 항목이다. 섹터 이름·업종/테마 구분·대표 ETF 이름을 함께 보인다(FR-003).
 *
 * 섹터를 고르는 체크박스가 여기 있고, 고르면 **같은 화면 그 자리에** 비중 입력 칸이 나타난다
 * (FR-005). 다른 화면으로 나누지 않는다. 대표 상품 정보 펼침은 M5가 더한다.
 *
 * **고르는 자리는 카드 전체다.** 체크박스·섹터 이름·구분·대표 ETF 이름을 `label` 하나로 묶어,
 * 그 안 어디를 눌러도 고르고 풀 수 있게 했다. 클릭을 가로채는 방식이 아니라 의미 구조로 푼
 * 것이라 키보드 조작과 보조 기술의 동작이 그대로 남는다.
 *
 * **비중을 다루는 요소는 그 `label` 밖에 둔다.** 안에 두면 슬라이더를 끌거나 숫자를 고치려다
 * 섹터가 풀린다. 보조 기술이 읽는 체크박스의 이름은 `aria-labelledby`로 **섹터 이름 하나**로
 * 묶어 둔다 — 그러지 않으면 카드 안의 글자가 모두 이름으로 읽힌다.
 *
 * 계산식·자료 공급원·거리 함수·실험 식별자·서버 설정을 보이지 않는다(FR-011). 그래서 응답이
 * 함께 주는 `instrumentId`·`benchmarkIndex`·`listedOn`을 여기서 그리지 않는다. 상장일은 M4가
 * 안내 문장을 만드는 데 쓰고 화면에 날것으로 늘어놓지 않는다.
 */
export function SectorListItem({
  sector,
  selected,
  onSelectedChange,
  weight,
  className,
}: SectorListItemProps) {
  const nameId = useId()

  return (
    <li className={className} data-sector-code={sector.code} data-selected={selected}>
      <label data-slot="choice">
        <input
          type="checkbox"
          checked={selected}
          onChange={(event) => onSelectedChange(event.target.checked)}
          aria-labelledby={nameId}
          data-slot="choice-box"
        />
        <span data-slot="heading">
          <strong id={nameId} data-slot="name">
            {sector.name}
          </strong>
          <span data-slot="classification">{classificationLabels[sector.classification]}</span>
        </span>

        <span data-slot="etf">
          <span data-slot="etf-label">대표 ETF</span>
          <span data-slot="etf-name">{sector.representativeEtf.name}</span>
        </span>
      </label>

      {/* label 밖이다. 비중을 만지려다 섹터가 풀리지 않게 하는 자리다. */}
      {weight ? (
        <WeightInput
          sectorName={sector.name}
          valueTenths={weight.valueTenths}
          onCommit={weight.onCommit}
        />
      ) : null}
    </li>
  )
}
