import { useId } from 'react'
import { Check, ChevronDown, Plus } from 'lucide-react'
import { InstrumentProfilePanel } from './InstrumentProfilePanel'
import type { SectorSummary } from '@/types/sector'

type InstrumentDetailProps = {
  /** 지금 보고 있는 섹터와 그 대표 ETF다. */
  sector: SectorSummary
  /** 이미 백테스트 대상으로 담아 두었는가. */
  selected: boolean
  /** 상세까지 펼쳐 보고 있는가. 이 상태는 항목이 바뀌어도 유지된다. */
  expanded: boolean
  onSelectedChange: (selected: boolean) => void
  onExpandedChange: (expanded: boolean) => void
  className?: string
}

/**
 * 지금 보고 있는 투자 대상 하나를 설명한다. 목록에서 고른 항목이 이 자리에 나타난다.
 *
 * **기본 상태에서는 정보를 줄인다.** 여러 상품을 빠르게 견주는 동안 필요한 것은 무엇을 담는
 * 상품인지와 기준 지수·운용사·상장일뿐이다. 보유 구성과 거래대금, 자료의 한계까지 처음부터
 * 늘어놓으면 견주는 일이 느려진다. 나머지는 `상세 보기`로 **같은 자리에서** 펼친다 — 다른
 * 화면으로 보내면 고르던 맥락이 끊기고, 돌아왔을 때 어디까지 봤는지 다시 찾아야 한다.
 *
 * **담는 일은 이 화면에서 단추 하나가 맡는다.** 목록을 누르는 것은 보는 일이고, 담는 것은
 * 여기서 한 번 더 누르는 일이다. 둘을 갈라 두어야 사용자가 무엇을 담았는지 스스로 안다.
 *
 * 비중은 여기서 다루지 않는다. 이 화면이 정하는 것은 "무엇을"이고 "얼마나"는 다음 단계의 몫이다.
 */
export function InstrumentDetail({
  sector,
  selected,
  expanded,
  onSelectedChange,
  onExpandedChange,
  className,
}: InstrumentDetailProps) {
  const headingId = useId()
  const profileId = useId()

  return (
    <article className={className} aria-labelledby={headingId} data-sector-code={sector.code}>
      <header data-slot="detail-header">
        <div data-slot="detail-title">
          <h3 id={headingId} data-slot="detail-name">
            {sector.representativeEtf.name}
          </h3>
          <p data-slot="detail-sector">{sector.name}</p>
        </div>

        {/*
          펼침 상태를 항목마다 따로 기억하지 않는다. 상세를 보며 여러 상품을 견주는 사용자는
          다음 항목에서도 같은 깊이로 보려 하므로, 항목을 옮길 때마다 다시 펼치게 하면 그 일을
          방해한다.
        */}
        <button
          type="button"
          data-slot="detail-expand"
          aria-expanded={expanded}
          aria-controls={profileId}
          onClick={() => onExpandedChange(!expanded)}
        >
          <span>{expanded ? '간단히 보기' : '상세 보기'}</span>
          <ChevronDown data-slot="detail-chevron" size={15} strokeWidth={1.8} aria-hidden="true" />
        </button>
      </header>

      {/*
        같은 패널이 두 깊이를 그린다. 펼치고 접을 때 다시 불러오지 않으므로, 상세를 여닫는
        동안 화면이 비었다가 채워지는 일이 없다.
      */}
      <InstrumentProfilePanel
        sectorCode={sector.code}
        id={profileId}
        variant={expanded ? 'full' : 'summary'}
      />

      {/*
        담고 빼는 자리다. 담긴 뒤에도 같은 자리에 남아 상태를 말하고, 한 번 더 누르면 뺀다.
        담긴 사실을 색이 아니라 글자로 먼저 알린다.
      */}
      <button
        type="button"
        data-slot="detail-action"
        data-selected={selected}
        aria-pressed={selected}
        onClick={() => onSelectedChange(!selected)}
      >
        {selected ? (
          <>
            <Check size={16} strokeWidth={2.2} aria-hidden="true" />
            <span>선택됨 · 빼기</span>
          </>
        ) : (
          <>
            <Plus size={16} strokeWidth={2.2} aria-hidden="true" />
            <span>투자 대상에 추가</span>
          </>
        )}
      </button>
    </article>
  )
}
