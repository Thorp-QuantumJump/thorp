import { ArrowRight, X } from 'lucide-react'
import type { ReactNode } from 'react'
import { InlineHelp } from '@/components/common/InlineHelp'
import type { SectorSummary } from '@/types/sector'

type SelectedInstrumentsRailProps = {
  /** 표시 순서로 정렬된, 지금 담아 둔 항목들이다. */
  selected: readonly SectorSummary[]
  /** 다음 단계로 넘어가는 데 필요한 최소 개수다. */
  minimumCount: number
  onRemove: (code: string) => void
  onProceed: () => void
  /** 다음 단계로 넘길 수 없는 상태인가. 조건을 채우지 못했거나 인계가 막힌 경우다. */
  proceedDisabled: boolean
  /** 상세 카드를 위한 폭을 남기고 선택 개수와 진행 가능 여부만 보이는가. */
  compact?: boolean
  /** 목록과 단추 사이에 놓을 안내다. 상장일 안내처럼 선택에 딸린 사실이 여기 들어온다. */
  children?: ReactNode
  className?: string
}

/**
 * 지금까지 담아 둔 투자 대상을 화면 오른쪽에 계속 보인다.
 *
 * **사용자의 기억에 기대지 않는다.** 목록을 오르내리며 상품을 살피는 동안 무엇을 담았는지는
 * 눈앞에 남아 있어야 한다. 그래서 이 자리는 상세를 펼치거나 다른 상품으로 옮겨도 사라지지
 * 않으며, 화면을 내려도 따라온다.
 *
 * 담은 것을 **여기서 바로 뺄 수 있다.** 빼려고 그 상품을 다시 찾아 들어가야 한다면, 목록이
 * 길어질수록 담은 것을 고치는 일이 어려워진다.
 *
 * 하나도 없을 때 빈 자리를 두지 않는다. 무엇을 해야 하는지 한 문장으로 알리고, 다음 단계로
 * 가는 단추는 **감추지 않고** 눌리지 않는 채로 둔다 — 무엇을 더 해야 넘어갈 수 있는지가 그
 * 문장과 함께 읽혀야 한다.
 */
export function SelectedInstrumentsRail({
  selected,
  minimumCount,
  onRemove,
  onProceed,
  proceedDisabled,
  compact = false,
  children,
  className,
}: SelectedInstrumentsRailProps) {
  const count = selected.length
  const remainingCount = Math.max(minimumCount - count, 0)
  const ready = remainingCount === 0

  return (
    <aside
      className={className}
      data-compact={compact}
      aria-labelledby="selected-instruments-title"
    >
      {/*
        고른 대상이 무엇을 뜻하는지(FR-009)는 제목 옆 물음표 뒤에 둔다. '함께 비교할 수 있는 기간'과 같은 짜임이다.
        카드 본문에 늘 펼쳐 두면 목록과 다음 단계 버튼 사이가 길어진다(2026-09-29).
      */}
      <div data-slot="rail-head">
        <h2
          id="selected-instruments-title"
          data-slot="rail-title"
          aria-label="선택한 투자 대상"
        >
          <span data-slot="rail-title-full">선택한 투자 대상</span>
          <span data-slot="rail-title-compact">선택</span>
        </h2>
        <InlineHelp label="선택한 투자 대상" side="bottom">
          <p>
            고른 투자 대상은 자동 배분 전략이 고를 수 있는 후보 집합입니다. 매번 모두 보유한다는 뜻이 아니며,
            '고정 비중' 전략에 쓰이는 비중은 다음 단계에서 정합니다.
          </p>
        </InlineHelp>
      </div>

      <div data-slot="rail-compact-summary" aria-live="polite">
        <strong data-slot="rail-compact-count">{count}</strong>
        <span data-slot="rail-compact-unit">개 선택</span>
        <span data-slot="rail-compact-status" data-ready={ready}>
          <span data-slot="rail-compact-dot" aria-hidden="true" />
          {ready ? '진행 가능' : `${remainingCount}개 더`}
        </span>
      </div>

      {count === 0 ? (
        <p data-slot="rail-empty">
          아직 선택한 투자 대상이 없습니다. 왼쪽에서 ETF를 확인한 뒤 백테스트할 대상을 추가해
          주세요.
        </p>
      ) : (
        <ul data-slot="rail-list" role="list">
          {selected.map((sector) => (
            <li key={sector.code} data-slot="rail-item" data-sector-code={sector.code}>
              <span data-slot="rail-item-body">
                <strong data-slot="rail-item-name">{sector.representativeEtf.name}</strong>
                <span data-slot="rail-item-sector">{sector.name}</span>
              </span>

              {/*
                아이콘만으로는 보조 기술이 읽을 이름이 없다. 어느 항목을 빼는 단추인지까지
                이름에 담는다 — 목록에서 셋째 단추가 무엇을 빼는지 소리만 듣고는 알 수 없다.
              */}
              <button
                type="button"
                data-slot="rail-remove"
                aria-label={`${sector.name} 빼기`}
                onClick={() => onRemove(sector.code)}
              >
                <X size={15} strokeWidth={2} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {/*
        개수는 목록이 길어져 끝이 화면 밖으로 나가도 얼마를 담았는지 알려 준다. 목록이 바뀔 때
        보조 기술에도 그 사실이 전해지도록 살아 있는 영역으로 둔다.
      */}
      <p data-slot="rail-count" aria-live="polite">
        {count > 0 ? `${count}개 선택됨` : ''}
      </p>

      {/* 비교하려면 나눌 곳이 둘은 있어야 한다. 하나만 담으면 어떤 배분 전략도 같은 결과를 낸다. */}
      {count > 0 && count < minimumCount ? (
        <p data-slot="rail-constraint" role="status">
          비교하려면 투자 대상을 {minimumCount}개 이상 골라야 합니다.
        </p>
      ) : null}

      <div data-slot="rail-supporting">{children}</div>

      <button
        type="button"
        data-slot="rail-proceed"
        onClick={onProceed}
        disabled={proceedDisabled}
        aria-label="다음: 전략 고르기"
      >
        <span data-slot="rail-proceed-full">다음: 전략 고르기</span>
        <span data-slot="rail-proceed-compact">
          <span>전략 고르기</span>
          <ArrowRight size={16} strokeWidth={2} aria-hidden="true" />
        </span>
      </button>

    </aside>
  )
}
