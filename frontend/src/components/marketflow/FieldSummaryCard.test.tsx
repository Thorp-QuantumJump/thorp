import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { FieldSummaryCard } from './FieldSummaryCard'

describe('FieldSummaryCard', () => {
  it('요약 문장이 없으면 빈칸 대신 자료 상태를 보여 준다', () => {
    render(<FieldSummaryCard onOpen={vi.fn()} field={{ fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'EMPTY', narrative: null, narrativeSubjects: [], referenceMonth: null, referenceMonthHeldBackBy: [], lastUpdatedAt: null, isExampleData: false }} />)
    expect(screen.getByText('표시할 자료 없음')).toBeTruthy()
    expect(screen.getByText(/변화 요약 대신/)).toBeTruthy()
  })
})
