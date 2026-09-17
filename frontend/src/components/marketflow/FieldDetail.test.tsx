import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { FieldDetailResult } from '@/types/marketFlow'
import { FieldDetail } from './FieldDetail'

/** 기준월만 바꿔 가며 쓰기 위한 최소 상세다. */
function detailOf(referenceMonth: string | null): FieldDetailResult {
  return {
    fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'READY',
    narrative: '변화', narrativeSubjects: [], referenceMonth,
    referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false,
    multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [],
  }
}

describe('FieldDetail', () => {
  it('기준월 뒤 발표 값은 3개월 경로 밖의 별도 안내로 보여 준다', () => {
    render(<FieldDetail detail={{ fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'READY', narrative: '변화', narrativeSubjects: [], referenceMonth: '2026-08', referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false, multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [{ indicatorId: 'unemployment', name: '실업률', unit: '%', value: 2.9, path: [{ month: '2026-06', value: 2.8 }, { month: '2026-07', value: 2.8 }, { month: '2026-08', value: 2.9 }], referenceMonth: '2026-08', releaseDate: '2026-09-10', source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: { referenceMonth: '2026-09', value: 3.1, unit: '%' } }] }} />)
    const path = screen.getByRole('list', { name: '실업률 3개월 경로' })
    expect(within(path).queryByText('3.1 %')).toBeNull()
    expect(screen.getByText(/기준월 뒤 발표 · 2026-09 3.1 %/)).toBeTruthy()
  })
  describe("'비슷했던 때 보기'로 넘기는 값 (T027, FR-009)", () => {
    it('분야와 그 분야의 기준월을 실어 둔다', () => {
      render(<FieldDetail detail={detailOf('2026-08')} />)

      const button = screen.getByRole('button', { name: /비슷했던 때 보기/ })
      expect(JSON.parse(button.getAttribute('data-handoff') ?? 'null')).toEqual({
        fieldId: 'labor',
        referenceMonth: '2026-08',
      })
    })

    it('기준월이 없으면 넘길 값을 만들지 않고 그 사유를 보인다', () => {
      render(<FieldDetail detail={detailOf(null)} />)

      const button = screen.getByRole('button', { name: /비슷했던 때 보기/ })
      expect(button.getAttribute('data-handoff')).toBeNull()
      expect(screen.getByText(/기준월이 아직 정해지지 않아/)).toBeTruthy()
    })

    // 010이 아직 없다. 여기서 임의의 목적지를 만들면 나중에 계약이 두 벌이 된다.
    it('목적지가 없는 동안은 눌리지 않는다', () => {
      render(<FieldDetail detail={detailOf('2026-08')} />)

      expect(screen.getByRole('button', { name: /비슷했던 때 보기/ })).toHaveProperty('disabled', true)
    })
  })
})
