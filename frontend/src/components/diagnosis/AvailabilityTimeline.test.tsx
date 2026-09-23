import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AvailabilityTimeline } from './AvailabilityTimeline'
import { buildAvailabilityTimeline } from '@/diagnosis/availabilityTimeline'
import type { SectorSummary } from '@/types/sector'

function sector(code: string, name: string, listedOn: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn,
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const 반도체 = sector('SEMICONDUCTOR', '반도체', '2006-06-27')
const 자동차 = sector('AUTO', '자동차', '2011-04-12')
const 조선 = sector('SHIPBUILDING', '조선', '2018-09-12')

const 오늘 = '2026-09-17'

function renderTimeline(sectors: SectorSummary[]) {
  return render(
    <AvailabilityTimeline timeline={buildAvailabilityTimeline(sectors, 오늘)} />,
  )
}

describe('비교 가능 구간 타임라인', () => {
  it('그릴 것이 없으면 자리 자체를 만들지 않는다', () => {
    const { container } = renderTimeline([])
    expect(container.querySelector('[data-slot="timeline"]')).toBeNull()
  })

  it('하나만 골라도 그 대상의 기간을 보인다', () => {
    const { container } = renderTimeline([반도체])

    expect(container.querySelector('[data-slot="timeline"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="timeline-range"]')?.textContent).toContain(
      '2006-06-27',
    )
    expect(container.querySelector('[data-slot="common-bar"]')).not.toBeNull()
  })

  it('개별 대상 막대 없이 공통 구간 한 줄만 그린다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    expect(container.querySelectorAll('[data-slot="common-bar"]')).toHaveLength(1)
    expect(container.querySelector('[data-slot="timeline-track"]')).toBeNull()
    expect(container.querySelector('[data-slot="track-bar"]')).toBeNull()
  })

  it('공통 시작일과 오늘 날짜를 기본 화면에 보인다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    const row = container.querySelector('[data-slot="timeline-row"]')
    const range = container.querySelector('[data-slot="timeline-range"]')?.textContent ?? ''
    expect(row?.children[0]?.getAttribute('data-slot')).toBe('timeline-plot')
    expect(
      container.querySelector('[data-slot="timeline-start-date"]')?.textContent,
    ).toBe('2018-09-12')
    expect(
      container.querySelector('[data-slot="timeline-end-date"]')?.textContent,
    ).toContain(오늘)
    expect(range).toContain('2018-09-12')
    expect(range).toContain(오늘)
    expect(range).toContain('오늘')
  })

  it('축 하나가 기간 길이를 보조 기술에 설명한다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    const plot = container.querySelector('[data-slot="timeline-plot"]')
    expect(plot?.getAttribute('role')).toBe('img')
    expect(plot?.getAttribute('aria-label')).toContain('2018-09-12')
    expect(plot?.getAttribute('aria-label')).toContain('8년')
    expect(plot?.getAttribute('tabindex')).toBe('0')
  })

  it('추가 정보에는 시작일을 정한 대상과 모든 상장일이 들어 있다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    const tooltip = container.querySelector('[role="tooltip"]')?.textContent ?? ''
    expect(tooltip).toContain('조선')
    expect(tooltip).toContain('반도체 2006-06-27')
    expect(tooltip).toContain('자동차 2011-04-12')
    expect(tooltip).toContain('조선 2018-09-12')
  })

  it('공통 구간은 전체 축 안에서 실제 시작 비율만큼 떨어져 시작한다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    const left = Number.parseFloat(
      (container.querySelector('[data-slot="timeline-plot"]') as HTMLElement).style.getPropertyValue(
        '--timeline-start',
      ),
    )
    expect(left).toBeGreaterThan(0)
    expect(left).toBeLessThan(100)
  })

  it('최종 구간이 달라질 수 있다는 한계는 추가 정보에서 설명한다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    const caveat = container.querySelector('[data-slot="timeline-caveat"]')?.textContent ?? ''
    expect(caveat).toContain('상장일 기준')
    expect(caveat).toContain('자료 마감일')
    expect(caveat).toContain('전략 준비 기간')
  })

  it('조작하는 입력을 두지 않는다', () => {
    const { container } = renderTimeline([반도체, 자동차, 조선])

    expect(container.querySelector('input')).toBeNull()
    expect(container.querySelector('button')).toBeNull()
    expect(container.querySelector('[role="slider"]')).toBeNull()
  })
})
