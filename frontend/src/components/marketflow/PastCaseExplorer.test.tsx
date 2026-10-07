import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { recordRegimeSearch } from '@/api/myRecords'
import { getRegimeCaseDetail, getRegimeSearch } from '@/api/regime'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { MemoryRouter } from 'react-router-dom'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { RegimeBaselineMonths, RegimeCaseDetail, RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { PastCaseExplorer } from './PastCaseExplorer'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))
vi.mock('@/api/myRecords', () => ({ recordRegimeSearch: vi.fn() }))

const baselines: RegimeBaselineMonths = {
  byField: [{ id: 'production', name: '생산', baselineMonth: '2026-07' }],
  composite: '2026-07',
  range: { earliest: '1962-01', latest: '2026-08' },
  note: '전체 분야를 맞추기 위해 비교 기준월이 바뀝니다.',
}

function cases(count: number): RegimeCaseSummary[] {
  return Array.from({ length: count }, (_, index) => ({
    endMonth: `2012-${String(index + 1).padStart(2, '0')}`,
    rank: index + 1,
    observationMonths: [`2011-${String(index + 11).padStart(2, '0')}`, `2012-${String(index + 1).padStart(2, '0')}`],
    distance: '0.41',
    similar: [`비슷한 점 ${index + 1}`, `비슷한 점 ${index + 1}의 둘째 문장`],
    different: [`다른 점 ${index + 1}`],
    detailAvailable: true,
  }))
}

function searchResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'S-1',
    mode: 'FIELD',
    field: { id: 'production', name: '생산' },
    baselineMonth: '2026-07',
    baselineOrigin: 'T_G',
    observationMonths: [],
    comparisonMonths: [],
    status: 'NEAREST_ONLY',
    statusReason: 'THRESHOLD_NOT_CALIBRATED',
    statusNote: '가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.',
    caseLabel: '최근접 참고 사례',
    displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    basis: { kind: 'HISTORICAL_REVISION', note: '최신 개정자료를 이용한 역사 비교입니다.' },
    dataAsOf: {
      indicators: [{ indicatorId: 'i1', indicator: '시험 지표', targetMonth: '2026-07', publishedAt: '2026-08-30' }],
      note: '생산 지표는 2026년 7월분까지 반영되어 있습니다.',
      revisions: [],
      newerReleases: [],
    },
    versions: { data: 'v1', registry: 'r1', model: 'm1', revision: 1, sampleData: false, sampleRegistry: false },
    counts: { evaluated: 400, eligible: 12, stored: 10, displayed: 3 },
    cases: cases(3),
    ...overrides,
  }
}

function caseDetail(searchId: string, endMonth: string): RegimeCaseDetail {
  return {
    searchId, endMonth, observationMonths: ['2011-11', endMonth], distance: '0.41', byField: {}, indicators: [], eraPosition: null,
    postPeriod: { endMonth, available: false, note: '사후 구간은 전략 연결에서 봅니다.' },
  } as unknown as RegimeCaseDetail
}

type Props = Parameters<typeof PastCaseExplorer>[0]

function renderExplorer(overrides: Partial<Props> = {}, user: AuthContextValue['user'] = null) {
  const props: Props = {
    id: 'past-cases',
    mode: 'field',
    fieldId: 'production',
    scopeName: '생산',
    base: { status: 'ready', result: searchResult() },
    onRetryBase: vi.fn(),
    baselines,
    fieldNames: { production: '생산' },
    ...overrides,
  }
  const auth = user ? ({ status: 'authenticated', user, login: vi.fn(), logout: vi.fn(), recheck: vi.fn(), updateNickname: vi.fn() } as unknown as AuthContextValue) : null
  return { props, ...renderWithRouter(<AuthContext.Provider value={auth}><PastCaseExplorer {...props} /></AuthContext.Provider>) }
}

describe('PastCaseExplorer (009 FR-019, 2026-10-07)', () => {
  beforeEach(() => {
    vi.mocked(getRegimeSearch).mockReset()
    vi.mocked(getRegimeCaseDetail).mockReset()
    vi.mocked(getRegimeCaseDetail).mockImplementation(async (endMonth, context) => caseDetail('searchId' in context ? context.searchId : '', endMonth))
    vi.mocked(recordRegimeSearch).mockReset()
  })

  it('가까운 사례를 먼저 보이고, 신뢰 범위 표시와 자료 기준 시점은 그 아래 늘 펼쳐 둔다 (010 FR-037)', () => {
    renderExplorer()

    const list = screen.getByRole('list', { name: '생산 2026-07 검색 결과: 가까운 과거 사례' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    expect(within(list).getByText('2011-11 ~ 2012-01')).toBeTruthy()
    // 문장이 많은 사례는 한 문장씩 먼저 보이고 '설명 더 보기'로 펼친다.
    expect(within(list).getByText('비슷한 점 1')).toBeTruthy()
    expect(within(list).queryByText('비슷한 점 1의 둘째 문장')).toBeNull()
    fireEvent.click(within(list).getAllByRole('button', { name: '설명 더 보기' })[0])
    expect(within(list).getByText('비슷한 점 1의 둘째 문장')).toBeTruthy()

    const status = screen.getByRole('status', { name: '생산 2026-07 검색 결과: 검색 상태' })
    expect(status.textContent).toContain('가까운 순서만 제공합니다.')
    expect(screen.getByRole('status', { name: '생산 2026-07 검색 결과: 사례 구분' }).textContent).toContain('최근접 참고 사례')
    expect(screen.getByRole('status', { name: '생산 2026-07 검색 결과: 역사 비교' }).textContent).toContain('역사 비교입니다.')
    const asOf = screen.getByRole('region', { name: '생산 2026-07 검색 결과: 자료 기준 시점' })
    expect(asOf.textContent).toContain('생산 지표는 2026년 7월분까지 반영되어 있습니다.')
    expect(asOf.textContent).toContain('대상월 2026-07 · 발표일 2026-08-30')
    // 사례가 신뢰 범위 표시보다 먼저 선다.
    expect(list.compareDocumentPosition(status) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('가까운 순서로 10개까지 보입니다.')).toBeTruthy()
  })

  it('기준월을 지정하면 그 달로 따로 조회하고 바뀐 사실을 알리며, 최신 기준월로 돌아갈 수 있다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(searchResult({ searchId: 'S-2015', baselineMonth: '2015-03', baselineOrigin: 'USER_SPECIFIED', cases: cases(1) }))
    renderExplorer()

    const input = screen.getByLabelText('기준월 지정')
    expect(input.getAttribute('min')).toBe('1962-01')
    expect(input.getAttribute('max')).toBe('2026-07')
    const find = screen.getByRole('button', { name: '이 달로 찾기' })
    expect(find).toHaveProperty('disabled', true)
    fireEvent.change(input, { target: { value: '2015-03' } })
    fireEvent.click(find)

    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'field', field: 'production', baselineMonth: '2015-03', limit: 3 }, expect.any(AbortSignal)))
    expect(await screen.findByText('기준월을 2015-03로 바꾸어 새 검색을 열었습니다.')).toBeTruthy()
    expect(screen.getByText('2015-03', { selector: 'strong' })).toBeTruthy()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: '최신 기준월로' }))
    expect(screen.getByText('기준월을 2026-07로 바꾸어 새 검색을 열었습니다.')).toBeTruthy()
    expect(within(screen.getByRole('list', { name: /가까운 과거 사례/ })).getAllByRole('listitem')).toHaveLength(3)
    expect(screen.queryByRole('button', { name: '최신 기준월로' })).toBeNull()
    // 최신 기준월은 페이지가 받은 검색을 그대로 쓴다 — 다시 받지 않는다.
    expect(getRegimeSearch).toHaveBeenCalledTimes(1)
  })

  it('최신 계산월보다 뒤의 달을 지정하면 최신 계산월로 물러나고 따로 조회하지 않는다 (010 FR-031)', () => {
    renderExplorer()

    const input = screen.getByLabelText('기준월 지정') as HTMLInputElement
    fireEvent.change(input, { target: { value: '2026-12' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    expect(input.value).toBe('2026-07')
    expect(getRegimeSearch).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '최신 기준월로' })).toBeNull()
  })

  it('더 보기는 열 개를 받는 동안 받아 둔 세 개를 그대로 두고, 받으면 열 개를 보인다', async () => {
    let resolveMore: (value: RegimeSearchResult) => void = () => {}
    vi.mocked(getRegimeSearch).mockImplementation(() => new Promise((resolve) => { resolveMore = resolve }))
    renderExplorer()

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'field', field: 'production', limit: 10 }, expect.any(AbortSignal)))
    expect(screen.getByRole('button', { name: '불러오는 중' })).toHaveProperty('disabled', true)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)

    resolveMore(searchResult({ counts: { evaluated: 400, eligible: 12, stored: 10, displayed: 10 }, cases: cases(10) }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(10))
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '불러오는 중' })).toBeNull()
  })

  it('상세 보기는 그 자리에서 사례 상세를 펼치고 사후 구간 자리에 백테스트 진입을 둔다 (011 진입 계약 §4)', async () => {
    renderExplorer()

    const first = screen.getAllByRole('listitem')[0]
    fireEvent.click(within(first).getByRole('button', { name: '상세 보기' }))
    await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2012-01', { searchId: 'S-1' }, expect.any(AbortSignal)))
    expect(await within(first).findByRole('heading', { level: 4, name: '사례 상세 · 2012-01' })).toBeTruthy()
    expect(within(first).getAllByRole('link', { name: '이 기간으로 백테스트' })).toHaveLength(2)
    expect(within(first).getByRole('button', { name: '상세 닫기' }).getAttribute('aria-expanded')).toBe('true')
  })

  it('위 줄의 자세히 요청을 받으면 그 사례의 상세를 펼치고 초점을 옮기며, 지정한 달은 푼다', async () => {
    const { props, rerender } = renderExplorer()
    expect(screen.queryByRole('button', { name: '상세 닫기' })).toBeNull()

    rerender(
      <MemoryRouter>
        <AuthContext.Provider value={null}>
          <PastCaseExplorer {...props} focusRequest={{ scope: 'production', endMonth: '2012-02', nonce: 1 }} />
        </AuthContext.Provider>
      </MemoryRouter>,
    )

    const second = screen.getAllByRole('listitem')[1]
    const toggle = await within(second).findByRole('button', { name: '상세 닫기' })
    await waitFor(() => expect(document.activeElement).toBe(toggle))
    await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2012-02', { searchId: 'S-1' }, expect.any(AbortSignal)))
  })

  it('다른 범위로 보낸 요청은 무시한다', () => {
    renderExplorer({ focusRequest: { scope: 'composite', endMonth: '2012-02', nonce: 1 } })

    expect(screen.queryByRole('button', { name: '상세 닫기' })).toBeNull()
  })

  it('종합 비교에서 충족 사례가 있으면 참고 사례는 눌러야 열린다 (010 FR-021)', () => {
    renderExplorer({
      mode: 'composite',
      fieldId: undefined,
      scopeName: '종합',
      base: {
        status: 'ready',
        result: searchResult({
          mode: 'COMPOSITE', field: null, status: 'THRESHOLD_APPLIED', statusReason: null, caseLabel: null,
          statusNote: 'Thorp가 정한 유사성 기준으로 사례를 나눴습니다.',
          counts: { evaluated: 400, eligible: 12, stored: 1, displayed: 1 }, cases: cases(1),
          referenceLabel: '기준 미충족 참고 사례', referenceCases: cases(3).slice(1),
        }),
      },
    })

    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '기준 미충족 참고 사례 보기' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText('Thorp가 정한 유사성 기준을 넘지 못한, 가까운 순서의 참고 사례입니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '기준 미충족 참고 사례 접기' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('계산 불가면 사례 목록 없이 검색 상태 상자가 그 사유를 말한다', () => {
    renderExplorer({
      base: { status: 'ready', result: searchResult({ status: 'NOT_COMPUTABLE', statusReason: 'NO_CANDIDATE', statusNote: '비교할 수 있는 과거 시점이 없습니다.', cases: [], caseLabel: null, counts: { evaluated: 0, eligible: 0, stored: 0, displayed: 0 } }) },
    })

    expect(screen.getByRole('status', { name: '생산 2026-07 검색 결과: 검색 상태' }).textContent).toContain('비교할 수 있는 과거 시점이 없습니다.')
    expect(screen.getAllByText('비교할 수 있는 과거 시점이 없습니다.')).toHaveLength(1)
    expect(screen.queryByRole('list', { name: /가까운 과거 사례/ })).toBeNull()
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })

  it('최신 검색을 받지 못하면 그 자리에서 다시 시도한다', () => {
    const { props } = renderExplorer({ base: { status: 'failed', message: null } })

    expect(screen.getByText('과거 사례를 불러오지 못했습니다. 분야 지표는 그대로 볼 수 있습니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(props.onRetryBase).toHaveBeenCalled()
  })

  it('로그인 사용자가 기준월을 지정해 연 검색만 내 기록에 남긴다 (010 FR-032)', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(searchResult({ searchId: 'S-2015', baselineMonth: '2015-03', baselineOrigin: 'USER_SPECIFIED', cases: cases(1) }))
    vi.mocked(recordRegimeSearch).mockResolvedValue({} as never)
    renderExplorer({}, { id: 7, email: 'u@example.com', nickname: '부캉이' } as unknown as AuthContextValue['user'])

    expect(recordRegimeSearch).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('기준월 지정'), { target: { value: '2015-03' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    await waitFor(() => expect(recordRegimeSearch).toHaveBeenCalledWith('S-2015', 'MARKET_FLOW', 'USER_SPECIFIED'))
    expect(recordRegimeSearch).toHaveBeenCalledTimes(1)
  })
})
