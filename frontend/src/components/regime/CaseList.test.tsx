import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { CaseList } from './CaseList'

function cases(count: number): RegimeCaseSummary[] {
  return Array.from({ length: count }, (_, index) => ({
    endMonth: `2012-${String(index + 1).padStart(2, '0')}`,
    rank: index + 1,
    observationMonths: ['2012-01', '2012-02', '2012-03'],
    distance: '0.412938571034',
    similar: [`비슷한 점 ${index + 1}`],
    different: [`다른 점 ${index + 1}`],
    detailAvailable: true,
  }))
}

function result(displayed: number, stored = 10, eligible = 12): RegimeSearchResult {
  return {
    searchId: 'search-1', mode: 'FIELD', field: { id: 'test-field', name: '시험 분야' },
    baselineMonth: '2026-07', baselineOrigin: 'T_G', observationMonths: [],
    comparisonMonths: [], status: 'NEAREST_ONLY', statusReason: 'THRESHOLD_NOT_CALIBRATED',
    statusNote: '가까운 순서만 제공합니다.', caseLabel: '최근접 참고 사례',
    displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    basis: { kind: 'HISTORICAL_REVISION', note: '역사 비교입니다.' },
    dataAsOf: { indicators: [], note: '자료 기준', revisions: [], newerReleases: [] },
    versions: { data: 'v1', registry: 'r1', model: 'm1', revision: 1, sampleData: false, sampleRegistry: false },
    counts: { evaluated: 412, eligible, stored, displayed }, cases: cases(displayed),
  }
}

describe('사례 목록', () => {
  it('처음 세 개를 보이고 저장된 사례가 더 있을 때만 더 보기를 요청한다', () => {
    const onShowMore = vi.fn()
    const { rerender } = render(<CaseList result={result(3)} onShowMore={onShowMore} isLoading={false} context="시험 검색" />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3)
    expect(screen.getByText('2012-03')).toBeTruthy()
    expect(screen.queryByText('0.412938571034')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(onShowMore).toHaveBeenCalledOnce()
    rerender(<CaseList result={result(10)} onShowMore={onShowMore} isLoading={false} context="시험 검색" />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(10)
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.getByText('가까운 순서로 10개까지 보입니다.')).toBeTruthy()
    expect(screen.queryByText('412')).toBeNull()
    expect(screen.queryByText('12')).toBeNull()
  })

  it('후보가 둘이면 빈자리를 만들거나 더 보기를 표시하지 않는다', () => {
    render(<CaseList result={result(2, 2, 2)} onShowMore={vi.fn()} isLoading={false} context="시험 검색" />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.queryByText('가까운 순서로 10개까지 보입니다.')).toBeNull()
  })
})
