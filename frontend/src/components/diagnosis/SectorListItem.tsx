import { useId, useRef, useState } from 'react'
import { InstrumentProfilePanel } from './InstrumentProfilePanel'
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
  const panelId = useId()

  /**
   * 대표 상품 정보를 펼쳤는가.
   *
   * 고른 섹터에만 두지 않는다. 고르기 **전에** 무엇을 사는지 보고 정하는 것이 이 패널의 목적이라,
   * 고른 뒤에만 열 수 있으면 순서가 뒤집힌다(FR-010).
   */
  const [profileOpen, setProfileOpen] = useState(false)
  const toggleRef = useRef<HTMLButtonElement>(null)

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

      {/*
        대표 상품 정보를 펼치는 자리다. 이것도 `label` 밖이다 — 안에 두면 펼치려다 섹터가 고쳐진다.

        `button` 하나로 여닫으므로 Enter와 Space가 그대로 동작한다. 직접 만든 펼침 장치를 쓰면
        키보드 조작을 손으로 다시 구현해야 하고, 그러다 빠뜨린 부분만큼 못 쓰는 사람이 생긴다.
        `aria-expanded`와 `aria-controls`가 지금 상태와 여는 대상을 보조 기술에 알린다.
      */}
      <div
        data-slot="profile"
        onKeyDown={(event) => {
          // 펼친 안쪽에서 Esc로 닫고 초점을 버튼으로 돌려준다. 초점을 두고 오면 키보드만 쓰는
          // 사용자가 목록의 처음부터 다시 찾아 내려와야 한다.
          if (event.key !== 'Escape' || !profileOpen) return
          event.stopPropagation()
          setProfileOpen(false)
          toggleRef.current?.focus()
        }}
      >
        {/*
          이름을 카드에 이미 한 번 적었으므로 버튼에서 되풀이하지 않는다. 대신 보조 기술에는
          어느 섹터의 것인지 실어 준다 — 항목마다 버튼 이름이 똑같으면 목록에서 셋째 버튼이
          무엇을 여는 것인지 소리만 듣고는 알 수 없다.
        */}
        <button
          ref={toggleRef}
          type="button"
          data-slot="profile-toggle"
          aria-expanded={profileOpen}
          aria-controls={panelId}
          aria-label={`${sector.name}에 담긴 종목 보기`}
          onClick={() => setProfileOpen((open) => !open)}
        >
          담긴 종목 보기
        </button>

        {profileOpen ? (
          <InstrumentProfilePanel sectorCode={sector.code} id={panelId} />
        ) : null}
      </div>
    </li>
  )
}
