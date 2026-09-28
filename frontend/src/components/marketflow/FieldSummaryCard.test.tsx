import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { FieldSummaryCard } from './FieldSummaryCard'

describe('FieldSummaryCard', () => {
  it('요약 문장이 없으면 빈칸 대신 자료 상태를 보여 준다', () => {
    render(<FieldSummaryCard onOpen={vi.fn()} field={{ fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'EMPTY', narrative: null, narrativeSubjects: [], referenceMonth: null, referenceMonthHeldBackBy: [], lastUpdatedAt: null, isExampleData: false }} />)
    expect(screen.getByText('표시할 자료 없음')).toBeTruthy()
    expect(screen.getByText(/변화 요약 대신/)).toBeTruthy()
  })

  const ready = { fieldId: 'labor' as const, displayName: '노동', displayOrder: 4, status: 'READY' as const, narrative: '실업률이 올랐습니다.', narrativeSubjects: [], referenceMonth: '2026-08', referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false }

  it('정상 공급이면 상태 표를 달지 않고, 목록 머리와 같은 갱신 시각은 되풀이하지 않는다', () => {
    render(<FieldSummaryCard onOpen={vi.fn()} field={ready} listUpdatedAt="2026-09-15T21:10:00Z" />)
    expect(screen.queryByText('자료 확인')).toBeNull()
    expect(screen.getByText('대상월 2026-08')).toBeTruthy()
    expect(screen.queryByText(/갱신/)).toBeNull()
  })

  it('목록 머리와 갱신 시각이 다르면 제 시각을 적고, 예시 자료는 늘 밝힌다', () => {
    render(<FieldSummaryCard onOpen={vi.fn()} field={{ ...ready, isExampleData: true }} listUpdatedAt="2026-09-16T00:00:00Z" />)
    expect(screen.getByText('예시 자료')).toBeTruthy()
    expect(screen.getByText(/갱신 2026-09-16 06:10 KST/)).toBeTruthy()
  })
})
