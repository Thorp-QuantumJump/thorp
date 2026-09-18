import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { NewerReleases } from './NewerReleases'

describe('최신 개별 발표', () => {
  it('기준월과 분리된 안내로 대상월과 발표일을 표시한다', () => {
    render(<NewerReleases context="종합 2026-05 검색 결과" releases={[{ indicatorId: 'i', indicator: '시험 지표', month: '2026-07', publishedAt: '2026-09-12' }]} />)
    const notice = screen.getByRole('status', { name: '종합 2026-05 검색 결과: 더 최신인 개별 발표' })
    expect(notice.textContent).toContain('대상월 2026-07 · 발표일 2026-09-12')
  })
})
