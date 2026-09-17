import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FieldDetail } from './FieldDetail'

describe('FieldDetail', () => {
  it('기준월 뒤 발표 값은 3개월 경로 밖의 별도 안내로 보여 준다', () => {
    render(<FieldDetail detail={{ fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'READY', narrative: '변화', narrativeSubjects: [], referenceMonth: '2026-08', referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false, multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [{ indicatorId: 'unemployment', name: '실업률', unit: '%', value: 2.9, path: [{ month: '2026-06', value: 2.8 }, { month: '2026-07', value: 2.8 }, { month: '2026-08', value: 2.9 }], referenceMonth: '2026-08', releaseDate: '2026-09-10', source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: { referenceMonth: '2026-09', value: 3.1, unit: '%' } }] }} />)
    const path = screen.getByRole('list', { name: '실업률 3개월 경로' })
    expect(within(path).queryByText('3.1 %')).toBeNull()
    expect(screen.getByText(/기준월 뒤 발표 · 2026-09 3.1 %/)).toBeTruthy()
  })
})
