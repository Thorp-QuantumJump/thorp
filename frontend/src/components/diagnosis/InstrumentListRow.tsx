import { Check } from 'lucide-react'
import type { SectorSummary } from '@/types/sector'

type InstrumentListRowProps = {
  sector: SectorSummary
  /** 지금 오른쪽 상세 영역이 보여 주고 있는 항목인가. */
  viewing: boolean
  /** 백테스트 대상으로 담아 둔 항목인가. */
  selected: boolean
  onView: () => void
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

/**
 * 투자 대상 목록의 한 줄이다. 섹터 이름과 그 섹터의 대표 ETF 이름을 함께 보인다.
 *
 * **누르면 고르는 것이 아니라 보는 것이다.** 이 줄의 유일한 행동은 상세 영역을 이 항목으로
 * 바꾸는 것이며, 백테스트 대상에 담는 일은 상세 영역의 단추가 맡는다. 정보를 확인하려고
 * 누른 것이 곧 선택이 되면, 사용자는 무엇을 담았는지 모르는 채로 다음 단계에 이른다.
 *
 * 그래서 이 줄은 **두 가지 상태를 함께** 드러낸다. 지금 보고 있다는 사실(`aria-current`)과
 * 이미 담아 두었다는 사실(체크 표시)이다. 둘은 서로를 뜻하지 않는다 — 담아 둔 항목을 보고
 * 있을 수도, 담지 않은 항목을 보고 있을 수도 있다.
 */
export function InstrumentListRow({
  sector,
  viewing,
  selected,
  onView,
  className,
}: InstrumentListRowProps) {
  return (
    <li className={className} data-sector-code={sector.code} data-selected={selected}>
      {/*
        `button` 하나로 끝낸다. 직접 만든 누름 장치를 쓰면 Enter와 Space, 초점 표시를 손으로 다시
        구현해야 하고, 그러다 빠뜨린 만큼 키보드만 쓰는 사용자가 못 쓰게 된다.

        `aria-current`가 지금 보고 있는 항목을 알린다. 선택 여부는 아래 체크 표시의 글자가
        읽어 주므로 여기서 다시 말하지 않는다.
      */}
      <button
        type="button"
        data-slot="row"
        aria-current={viewing ? 'true' : undefined}
        onClick={onView}
      >
        <span data-slot="row-heading">
          <strong data-slot="row-name">{sector.name}</strong>
          <span data-slot="row-etf">{sector.representativeEtf.name}</span>
        </span>

        {/*
          담아 둔 항목이라는 표시다. 색만으로 알리지 않는다 — 색을 구분하지 못하는 사용자와
          보조 기술 사용자에게는 글자가 유일한 단서다(원칙 VI).
        */}
        {selected ? (
          <span data-slot="row-selected">
            <Check size={14} strokeWidth={2.2} aria-hidden="true" />
            <span data-slot="row-selected-text">선택됨</span>
          </span>
        ) : null}
      </button>
    </li>
  )
}
