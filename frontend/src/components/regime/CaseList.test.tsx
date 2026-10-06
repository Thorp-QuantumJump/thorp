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
    const { rerender } = render(<CaseList result={result(3)} onShowMore={onShowMore} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3)
    expect(screen.getByText('2012-03')).toBeTruthy()
    expect(screen.queryByText('0.412938571034')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(onShowMore).toHaveBeenCalledOnce()
    rerender(<CaseList result={result(10)} onShowMore={onShowMore} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(10)
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.getByText('가까운 순서로 10개까지 보입니다.')).toBeTruthy()
    expect(screen.queryByText('412')).toBeNull()
    expect(screen.queryByText('12')).toBeNull()
  })

  it('후보가 둘이면 빈자리를 만들거나 더 보기를 표시하지 않는다', () => {
    render(<CaseList result={result(2, 2, 2)} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.queryByText('가까운 순서로 10개까지 보입니다.')).toBeNull()
  })

  it('지금 시점 안내와 특이 사례의 당시 맥락을 검색 근거가 아닌 링크로 보인다', () => {
    const base = result(2, 2, 2)
    const withContext: RegimeSearchResult = {
      ...base,
      mode: 'COMPOSITE',
      field: null,
      phaseNote: '지금은 같은 흐름에 속한 지표들이 평소보다 엇갈려 움직이고 있습니다.',
      cases: [
        {
          ...base.cases[0],
          unusual: true,
          context: [
            { kind: 'KDI_TRENDS', date: '2009-01-05', title: 'KDI 경제동향 2009년 1월호', url: 'https://www.kdi.re.kr/research/monTrends?pub_no=1', source: 'KDI 한국개발연구원' },
            { kind: 'CYCLE_TURN', date: '2009-02-01', title: '경기 저점', source: '국가데이터처 기준순환일' },
          ],
        },
        base.cases[1],
      ],
    }
    render(<CaseList result={withContext} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getByText(withContext.phaseNote as string)).toBeTruthy()
    // 당시 맥락은 '설명 더 보기' 안에 있다(S15P21E205-450).
    expect(screen.queryByRole('link', { name: 'KDI 경제동향 2009년 1월호' })).toBeNull()
    fireEvent.click(screen.getAllByRole('button', { name: '설명 더 보기' })[0])
    const link = screen.getByRole('link', { name: 'KDI 경제동향 2009년 1월호' })
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(screen.getByText('경기 저점').tagName).toBe('SPAN')
    expect(screen.getAllByText(/검색에 쓰지 않았고/)).toHaveLength(1)
  })

  it('v1 결과에는 당시 맥락과 지금 시점 안내가 없다', () => {
    render(<CaseList result={result(2, 2, 2)} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.queryByText('당시 맥락')).toBeNull()
    expect(screen.queryByText('지금 시점')).toBeNull()
  })

  it('교정된 결과는 기준 충족 사례를 기본 목록에, 참고 사례는 눌러야 열리는 별도 목록에 보인다', () => {
    const base = result(2, 2, 2)
    const applied: RegimeSearchResult = {
      ...base,
      status: 'THRESHOLD_APPLIED',
      caseLabel: null,
      cases: [base.cases[0]],
      referenceLabel: '기준 미충족 참고 사례',
      referenceCases: [base.cases[1]],
      counts: { ...base.counts, stored: 1, displayed: 1 },
    }
    render(<CaseList result={applied} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(1)
    expect(screen.queryByText('2012-02')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '기준 미충족 참고 사례 보기' }))
    expect(screen.getByText('2012-02')).toBeTruthy()
    expect(screen.getByText(/Thorp가 정한 유사성 기준을 넘지 못한/)).toBeTruthy()
    expect(screen.queryByText('더 보기')).toBeNull()
  })

  it('기준 충족 사례가 없으면 안내와 분야별 탐색을 보인다', () => {
    const base = result(1, 1, 1)
    const onBrowseFields = vi.fn()
    const none: RegimeSearchResult = {
      ...base,
      mode: 'COMPOSITE',
      field: null,
      status: 'THRESHOLD_APPLIED',
      caseLabel: null,
      cases: [],
      thresholdNote: '설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.',
      referenceLabel: '기준 미충족 참고 사례',
      referenceCases: base.cases,
      counts: { ...base.counts, stored: 0, displayed: 0 },
    }
    render(<CaseList result={none} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} onBrowseFields={onBrowseFields} />)
    expect(screen.getByText(/충족하는 사례가 없습니다/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '분야별로 살펴보기' }))
    expect(onBrowseFields).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '기준 미충족 참고 사례 보기' })).toBeTruthy()
  })

  it('문장이 많은 사례는 한 문장씩만 먼저 보이고 설명 더 보기로 펼친다', () => {
    const base = result(1, 1, 1)
    const long: RegimeSearchResult = {
      ...base,
      cases: [
        {
          ...base.cases[0],
          similar: ['비슷 1', '비슷 2'],
          different: ['다름 1', '다름 2', '다름 3'],
        },
      ],
    }
    render(<CaseList result={long} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.getByText('비슷 1')).toBeTruthy()
    expect(screen.queryByText('비슷 2')).toBeNull()
    expect(screen.queryByText('다름 3')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '설명 더 보기' }))
    expect(screen.getByText('비슷 2')).toBeTruthy()
    expect(screen.getByText('다름 3')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '설명 접기' }))
    expect(screen.queryByText('다름 3')).toBeNull()
  })

  it('한 문장씩인 사례에는 펼치기 버튼이 없다', () => {
    render(<CaseList result={result(1, 1, 1)} onShowMore={vi.fn()} isLoading={false} context="시험 검색" fieldNames={{}} />)
    expect(screen.queryByRole('button', { name: '설명 더 보기' })).toBeNull()
  })
})
