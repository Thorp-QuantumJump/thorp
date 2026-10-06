import { fireEvent, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { RegimeCaseDetail, RegimeSearchResult } from '@/types/regime'
import { FieldPastCompare } from './FieldPastCompare'

function searchResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'FIELD:production:2026-07-01:macro-1:calc-1:REGIME-V1.1',
    mode: 'FIELD',
    field: { id: 'production', name: '생산' },
    baselineMonth: '2026-07',
    status: 'NEAREST_ONLY',
    statusNote: '가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.',
    caseLabel: '최근접 참고 사례',
    displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    versions: { data: 'macro-1', registry: 'calc-1', model: 'REGIME-V1.1', revision: 1, sampleData: false, sampleRegistry: false },
    comparisonMonths: [{ indicatorId: 'all_industry_production', indicator: '전산업생산', months: ['2026-05', '2026-06', '2026-07'], omitted: [] }],
    dataAsOf: { indicators: [], note: '생산 지표는 2026년 7월분까지 반영되어 있습니다.', revisions: [], newerReleases: [] },
    cases: [
      { endMonth: '2001-03', rank: 1, observationMonths: ['2001-01', '2001-02', '2001-03'], distance: '0.37', similar: ['전산업생산은 3.1%로 당시 2.6%와 비슷합니다.'], different: [], detailAvailable: true },
      { endMonth: '2011-10', rank: 2, observationMonths: ['2011-08', '2011-09', '2011-10'], distance: '0.45', similar: [], different: [], detailAvailable: true },
    ],
    ...overrides,
  } as RegimeSearchResult
}

type Props = ComponentProps<typeof FieldPastCompare>

function renderCompare(overrides: Partial<Props> = {}) {
  const props: Props = {
    fieldId: 'production',
    fieldName: '생산',
    referenceMonth: '2026-07',
    nowMonths: ['2026-05', '2026-06', '2026-07'],
    search: { status: 'ready', result: searchResult() },
    onRetrySearch: vi.fn(),
    selected: '2001-03',
    onSelect: vi.fn(),
    caseDetail: { status: 'ready', detail: {} as RegimeCaseDetail },
    onRetryCaseDetail: vi.fn(),
    pastUnavailable: false,
    pastMonths: ['2001-01', '2001-02', '2001-03'],
    band: { name: '지난 10년 보통 범위', window: '2016-05~2026-04' },
    showBand: false,
    onToggleBand: vi.fn(),
    unplaced: [],
    ...overrides,
  }
  return { props, ...renderWithRouter(<FieldPastCompare {...props} />) }
}

describe('FieldPastCompare (009 FR-019)', () => {
  it('지금만과 가까운 사례를 라디오로 고르고, 사례 칸에는 관측 구간만 적는다', () => {
    const { props } = renderCompare()

    const nearest = screen.getByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })
    expect((nearest as HTMLInputElement).checked).toBe(true)
    expect(screen.getByRole('radio', { name: '지금만' })).toBeTruthy()
    // 비슷한 점·다른 점은 지표 칸에 붙으므로 고르는 칸에는 적지 않는다(2026-10-02 사용자 결정).
    expect(screen.getByRole('group', { name: '그래프에 겹쳐 볼 과거 구간' }).textContent).not.toContain('비슷')

    fireEvent.click(screen.getByRole('radio', { name: '2011-08 ~ 2011-10' }))
    expect(props.onSelect).toHaveBeenLastCalledWith('2011-10')
    fireEvent.click(screen.getByRole('radio', { name: '지금만' }))
    expect(props.onSelect).toHaveBeenLastCalledWith(null)
  })

  it('백테스트는 011 진입 계약으로, 자세히·모두 보기는 010 검색을 분야와 010 기준월로 연다', () => {
    renderCompare({ referenceMonth: '2026-08' })

    expect(screen.getByRole('link', { name: /이 기간으로 백테스트/ }).getAttribute('href')).toBe(
      '/regime-link/start?case=FIELD%3Aproduction%3A2026-07-01%3Amacro-1%3Acalc-1%3AREGIME-V1.1%7E2001-03',
    )
    // 저장된 판(?searchId=)으로 열면 010은 '내 기록 다시 보기'로 다뤄 기록하지 않고 분야도 비워 둔다(010 FR-032).
    // 기준월은 분야 상세의 대상월이 아니라 010이 계산한 달이다(009 FR-009).
    expect(screen.getByRole('link', { name: '자세히' }).getAttribute('href')).toBe('/regime-search?field=production&month=2026-07')
    expect(screen.getByRole('link', { name: /모두 보기/ }).getAttribute('href')).toBe('/regime-search?field=production&month=2026-07')
  })

  it('지금만을 고르면 두 행동을 누를 수 없지만 보통 범위는 켤 수 있다', () => {
    const { props } = renderCompare({ selected: null })

    expect(screen.getByRole('button', { name: /이 기간으로 백테스트/ })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: '자세히' })).toHaveProperty('disabled', true)
    expect(screen.queryByText(/당시 2001/)).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: /지난 10년 보통 범위/ }))
    expect(props.onToggleBand).toHaveBeenCalledWith(true)
  })

  it('범례는 지금·당시 기간을 적고, 보통 범위의 기간은 도움말로 읽힌다', async () => {
    renderCompare()

    expect(screen.getByText('지금 2026.05 ~ 07')).toBeTruthy()
    expect(screen.getByText('당시 2001.01 ~ 03')).toBeTruthy()
    fireEvent.focus(screen.getByRole('button', { name: '지난 10년 보통 범위 도움말' }))
    expect((await screen.findByRole('tooltip')).textContent).toContain('2016-05~2026-04 자료의 중앙값 ± 산포')
  })

  it('당시 경로를 받지 못하면 그 자리에서만 다시 시도하고, 다른 사례의 응답은 섞지 않는다', () => {
    const { props, unmount } = renderCompare({ caseDetail: { status: 'failed' } })
    expect(screen.getByText('당시 경로를 불러오지 못했습니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(props.onRetryCaseDetail).toHaveBeenCalled()
    unmount()

    renderCompare({ caseDetail: { status: 'mismatch' } })
    expect(screen.getByText('요청한 사례 상세와 응답이 일치하지 않습니다.')).toBeTruthy()
  })

  it('상세를 열 수 없는 사례는 그 사실을 적는다', () => {
    renderCompare({ pastUnavailable: true, caseDetail: null })

    expect(screen.getByText('이 사례는 지표별 당시 경로를 볼 수 없습니다.')).toBeTruthy()
  })

  it('검색을 받지 못해도 경고로 막지 않고, 010으로 가는 길은 분야와 대상월로 남긴다', () => {
    const { props } = renderCompare({ search: { status: 'failed', message: null } })

    expect(screen.getByText('과거 사례를 불러오지 못했습니다. 분야 지표는 그대로 볼 수 있습니다.')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(props.onRetrySearch).toHaveBeenCalled()
    expect(screen.getByRole('link', { name: /모두 보기/ }).getAttribute('href')).toBe('/regime-search?field=production&month=2026-07')
  })

  it('검색 상태는 i 표시 뒤로 내리고 보조기기에는 늘 읽히며, 분야만의 유사성임을 밝힌다 (010 FR-037)', async () => {
    renderCompare()

    const live = screen.getByRole('status', { name: '생산 2026-07 검색 결과: 검색 상태와 사례 구분' })
    expect(live.textContent).toContain('검색 상태: 가까운 순서만 제공합니다.')
    fireEvent.focus(screen.getByRole('button', { name: '검색 상태와 사례 구분 도움말' }))
    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain('최근접 참고 사례')
    expect(tooltip.textContent).toContain('생산 분야만의 유사성')
  })

  it('010의 신뢰 범위 표시를 문구로 늘 보인다 (010 FR-037·FR-038·FR-031)', () => {
    renderCompare({
      search: {
        status: 'ready',
        result: searchResult({
          versions: { data: 'macro-1', registry: 'calc-1', model: 'REGIME-V1.1', revision: 1, sampleData: true, sampleRegistry: false },
          comparisonMonths: [{ indicatorId: 'all_industry_production', indicator: '전산업생산', months: ['2026-06', '2026-07'], omitted: ['2026-05'], note: '전산업생산 2026-05는 비교에서 뺐습니다.' }],
        }),
      },
    })

    expect(screen.getByText('이 결과는 아직 확정되지 않은 예시 자료로 계산되었습니다.')).toBeTruthy()
    expect(screen.getByText('전산업생산 2026-05는 비교에서 뺐습니다.')).toBeTruthy()
    expect(screen.getByLabelText('생산 2026-07 검색 결과: 자료 기준 시점').textContent).toContain('2026년 7월분까지 반영')
  })

  it('비교 기준월이 대상월과 다르면 그 사실을 밝힌다 (009 FR-009)', () => {
    renderCompare({ referenceMonth: '2026-08' })

    expect(screen.getByText(/과거 비교는 2026-07 기준입니다/)).toBeTruthy()
  })

  it('지표 칸에 붙지 못한 문장은 고르는 줄 아래에 보인다', () => {
    renderCompare({ unplaced: [{ kind: 'different', text: '건설기성은 지금이 더 낮습니다.' }] })

    expect(screen.getByText('다른 점')).toBeTruthy()
    expect(screen.getByText('건설기성은 지금이 더 낮습니다.')).toBeTruthy()
  })

  it('사례가 없으면 검색 상태 문장을 보이고, 분야 상세의 제목 차례를 뒤집지 않는다', () => {
    renderCompare({ search: { status: 'ready', result: searchResult({ status: 'NOT_COMPUTABLE', statusNote: '비교할 자료가 부족합니다.', cases: [] }) } })

    expect(screen.getByText('비교할 자료가 부족합니다.')).toBeTruthy()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
    expect(screen.queryByRole('radio')).toBeNull()
  })

  it('화면이 추천 표현을 만들지 않는다', () => {
    const { container } = renderCompare()

    for (const forbidden of ['추천', '1위', '최적']) expect(container.textContent).not.toContain(forbidden)
  })
})
