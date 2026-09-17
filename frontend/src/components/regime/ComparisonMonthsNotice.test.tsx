import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ComparisonMonthsNotice } from './ComparisonMonthsNotice'

describe('빠진 비교 시점 표시', () => {
  it('빠진 달의 서버 문구를 숨기지 않고 보인다', () => {
    render(<ComparisonMonthsNotice context="시험 검색" comparisonMonths={[
      { indicatorId: 'one', indicator: '시험 지표', months: ['2026-05'], omitted: ['2026-06'], note: '시험 지표는 2026년 6월 자료가 없어 그 달을 빼고 비교했습니다.' },
    ]} />)
    expect(screen.getByText(/2026년 6월 자료가 없어/)).toBeTruthy()
    expect(screen.getByRole('status', { name: '시험 검색: 빠진 비교 시점' })).toBeTruthy()
  })

  it('빠진 달이 없으면 표시를 만들지 않는다', () => {
    const { container } = render(<ComparisonMonthsNotice context="시험 검색" comparisonMonths={[
      { indicatorId: 'one', indicator: '시험 지표', months: ['2026-05'], omitted: [] },
    ]} />)
    expect(container.childElementCount).toBe(0)
  })
})
