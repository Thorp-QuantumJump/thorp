import type { CSSProperties } from 'react'
import type { AvailabilityTimeline as Timeline } from '@/diagnosis/availabilityTimeline'

/**
 * 고른 투자 대상의 공통 비교 가능 구간 하나만 축 위에 그린다.
 *
 * 이 자리의 첫 질문은 "백테스트가 언제부터 언제까지 가능한가"다. 개별 대상의 상장 구간을 모두
 * 펼치면 가장 중요한 공통 구간이 여러 줄 가운데 묻히므로, 기본 화면에는 공통 구간만 남기고
 * 시작일을 정한 대상과 개별 상장일은 마우스·키보드 포커스 때 보이는 상세로 옮긴다.
 *
 * **판정은 이 컴포넌트가 하지 않는다.** `buildAvailabilityTimeline`이 돌려준 자리 값을 그대로
 * 그린다. 판정이 두 곳에 있으면 한쪽만 고쳐져 막대와 안내 문장이 서로 다른 날짜를 말한다.
 *
 * **조작하는 자리가 아니다.** 손잡이를 끌어 기간을 좁히는 입력을 두지 않는다. 분석 기간은 다음
 * 단계인 전략 설정 화면이 소유하고 있고, 여기서 고른 값을 넘길 인계 계약의 칸도 없다. 읽는
 * 그림에 끌리는 손잡이를 붙이면 사용자는 여기서 정한 기간이 다음 화면으로 이어진다고 읽는다.
 *
 * **공통 구간 자체는 접어 두지 않는다.** 원칙 II가 결과 의미를 바꾸는 자료 범위 표시를 상세 안에
 * 숨기지 못하게 한다. 상세에는 그 구간이 나온 근거만 둔다.
 *
 * 축 오른쪽 끝은 오늘이다. `GET /sectors` 응답에 자료 마감일이 없어 실제 마감일은 이보다 이를
 * 수 있고, 그 사실을 아래 한 줄로 적는다 — 없는 날짜를 지어내지 않는다(원칙 VI).
 */

type AvailabilityTimelineProps = {
  timeline: Timeline
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

/** 막대의 시작 자리를 백분율로 적는다. 자리 계산은 이미 끝나 있고 여기서는 단위만 붙인다. */
function leftOf(ratio: number): string {
  return `${(ratio * 100).toFixed(3)}%`
}

type TimelinePositionStyle = CSSProperties & {
  '--timeline-start': string
}

/** 시작일이 축 끝에 가까워도 날짜가 카드 밖으로 잘리지 않게 글자의 기준점을 바꾼다. */
function startLabelAlignment(ratio: number): 'start' | 'center' | 'end' {
  if (ratio <= 0.08) return 'start'
  if (ratio >= 0.78) return 'end'
  return 'center'
}

export function AvailabilityTimeline({ timeline, className }: AvailabilityTimelineProps) {
  if (timeline.status === 'silent') return null

  const { axisEnd, tracks, commonStart, commonStartRatio, bindingSectorName } = timeline
  const tooltipId = `availability-${tracks.map((track) => track.code).join('-')}`
  const positionStyle: TimelinePositionStyle = {
    '--timeline-start': leftOf(commonStartRatio),
  }

  return (
    <section
      className={className}
      aria-label="고른 투자 대상의 비교 가능 구간"
      data-slot="timeline"
    >
      <h2 data-slot="timeline-title">함께 비교할 수 있는 기간</h2>

      <div data-slot="timeline-row">
        <div
          data-slot="timeline-plot"
          role="img"
          tabIndex={0}
          style={positionStyle}
          aria-label={`상장일 기준 공통 비교 가능 구간은 ${commonStart}부터 오늘 ${axisEnd}까지, ${timeline.commonSpanLabel}입니다.`}
          aria-describedby={tooltipId}
        >
          <span data-slot="timeline-lane" aria-hidden="true">
            <span data-slot="common-bar">
              <span data-slot="common-start" />
              <span data-slot="common-end" />
            </span>
          </span>

          <p data-slot="timeline-range" aria-hidden="true">
            <strong
              data-slot="timeline-start-date"
              data-align={startLabelAlignment(commonStartRatio)}
            >
              {commonStart}
            </strong>
            <span data-slot="timeline-end-date">
              <strong>{axisEnd}</strong>
              <span data-slot="timeline-today">오늘</span>
            </span>
          </p>

          <div id={tooltipId} data-slot="timeline-tooltip" role="tooltip">
            <p>
              <strong>{bindingSectorName}</strong>이(가) {commonStart}에 상장해 시작일이
              정해졌습니다.
            </p>
            <p data-slot="timeline-listings">
              {tracks.map((track) => `${track.name} ${track.listedOn}`).join(' · ')}
            </p>
            <p data-slot="timeline-caveat">
              상장일 기준입니다. 실제 자료 마감일과 전략 준비 기간에 따라 최종 구간은 달라질 수
              있습니다.
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}
