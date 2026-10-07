import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketFlowField } from '@/api/marketFlow'
import { recordRegimeSearch } from '@/api/myRecords'
import { getRegimeCaseDetail, getRegimeSearch } from '@/api/regime'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { FieldDetailResult, FieldSummary } from '@/types/marketFlow'
import type { RegimeBaselineMonths, RegimeCaseDetail, RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { CompositePast } from './CompositePast'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))
vi.mock('@/api/marketFlow', () => ({ fetchMarketFlowField: vi.fn() }))
vi.mock('@/api/myRecords', () => ({ recordRegimeSearch: vi.fn() }))

const baselines: RegimeBaselineMonths = {
  byField: [{ id: 'production', name: '생산', baselineMonth: '2026-07' }, { id: 'labor', name: '노동', baselineMonth: '2026-07' }],
  composite: '2026-07',
  range: { earliest: '1962-01', latest: '2026-08' },
  note: '전체 분야를 맞추기 위해 비교 기준월이 바뀝니다.',
}

const fields: FieldSummary[] = [
  { fieldId: 'production', displayName: '생산', displayOrder: 1, status: 'READY', narrative: null, narrativeSubjects: [], referenceMonth: '2026-07', referenceMonthHeldBackBy: [], lastUpdatedAt: null, isExampleData: false },
  { fieldId: 'labor', displayName: '노동', displayOrder: 2, status: 'READY', narrative: null, narrativeSubjects: [], referenceMonth: '2026-07', referenceMonthHeldBackBy: [], lastUpdatedAt: null, isExampleData: false },
]

const productionDetail: FieldDetailResult = {
  ...fields[0],
  multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.',
  indicators: [{
    indicatorId: 'all_industry_production', name: '전산업생산', unit: '%', value: 3.1,
    path: [{ month: '2026-05', value: 1.9 }, { month: '2026-06', value: 4.4 }, { month: '2026-07', value: 3.1 }],
    referenceMonth: '2026-07', releaseDate: null, source: '국가통계포털(KOSIS)', agency: '통계청', afterReferenceMonthRelease: null,
  }],
}

function cases(count: number, startYear = 2019): RegimeCaseSummary[] {
  return Array.from({ length: count }, (_, index) => {
    const year = startYear - index
    return {
      endMonth: `${year}-08`, rank: index + 1, observationMonths: [`${year}-06`, `${year}-07`, `${year}-08`], distance: `${0.4 + index / 10}`,
      similar: [`실물 경기 흐름은 비슷했습니다. ${year}년 비슷한 점.`], different: [`대외 거래 흐름은 달랐습니다. ${year}년 다른 점.`], detailAvailable: true,
    }
  })
}

function compositeResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'C-1',
    mode: 'COMPOSITE',
    field: null,
    baselineMonth: '2026-07',
    baselineOrigin: 'T_ALL',
    observationMonths: [],
    status: 'THRESHOLD_APPLIED',
    statusReason: null,
    statusNote: 'Thorp가 정한 유사성 기준으로 사례를 나눴습니다.',
    caseLabel: null,
    displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    basis: { kind: 'HISTORICAL_REVISION', note: '최신 개정자료를 이용한 역사 비교입니다.' },
    counts: { evaluated: 10, eligible: 3, stored: 1, displayed: 1 },
    versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: false, sampleRegistry: false },
    comparisonMonths: [],
    dataAsOf: {
      indicators: [
        { indicatorId: 'exports', indicator: '수출', targetMonth: '2026-07', publishedAt: '2026-09-22' },
        { indicatorId: 'imports', indicator: '수입', targetMonth: '2026-07', publishedAt: '2026-09-22' },
      ],
      note: null,
      revisions: [],
      newerReleases: [{ indicatorId: 'cpi', indicator: '소비자물가', month: '2026-08', publishedAt: '2026-09-22' }],
    },
    cases: cases(1),
    ...overrides,
  }
}

function caseDetail(endMonth: string): RegimeCaseDetail {
  return {
    searchId: 'C-1', endMonth, observationMonths: ['2019-06', '2019-07', '2019-08'], distance: '0.4', byField: {}, eraPosition: null,
    blocks: [{ id: 'real', name: '실물 경기', gap: '0.3', spread: '1.0' }],
    indicators: [{
      id: 'all_industry_production', name: '전산업생산', unit: '%', comparisonMonths: ['2026-05', '2026-06', '2026-07'], caseMonths: ['2019-06', '2019-07', '2019-08'],
      queryRaw: ['1.9', '4.4', '3.1'], caseRaw: ['2.5', '5.0', '2.6'], queryZ: [], caseZ: [], e: '0.2', nominalWeight: '0.06',
      scale: { median: '2', spread: '3', method: 'MAD', windowStart: '2016-05', windowEnd: '2026-04', months: 120 },
    }],
    postPeriod: { endMonth, available: false, note: '사후 구간은 전략 연결에서 봅니다.' },
  }
}

function renderComposite(user: AuthContextValue['user'] = null) {
  const auth = user ? ({ status: 'authenticated', user, login: vi.fn(), logout: vi.fn(), recheck: vi.fn(), updateNickname: vi.fn() } as unknown as AuthContextValue) : null
  return renderWithRouter(
    <AuthContext.Provider value={auth}>
      <CompositePast fields={fields} baselines={baselines} fieldNames={{ production: '생산', labor: '노동' }} />
    </AuthContext.Provider>,
  )
}

describe('CompositePast (009 FR-019, 010 US3)', () => {
  beforeEach(() => {
    vi.mocked(getRegimeSearch).mockReset()
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult())
    vi.mocked(getRegimeCaseDetail).mockReset()
    vi.mocked(getRegimeCaseDetail).mockImplementation(async (endMonth) => caseDetail(endMonth))
    vi.mocked(fetchMarketFlowField).mockReset()
    vi.mocked(fetchMarketFlowField).mockImplementation(async (fieldId) => (
      fieldId === 'production' ? productionDetail : { ...fields[1], multipleReferenceMonthNotice: '', indicators: [] }
    ))
    vi.mocked(recordRegimeSearch).mockReset()
  })

  it('분야 상세와 같은 모양이다 — 가까운 사례 선택 줄 아래 일곱 분야의 지표 그래프에 고른 사례의 당시 경로를 겹친다', async () => {
    renderComposite()

    const nearest = await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })
    expect((nearest as HTMLInputElement).checked).toBe(true)
    expect(vi.mocked(getRegimeSearch).mock.calls[0][0]).toEqual({ mode: 'composite', limit: 10 })
    expect(screen.getByRole('heading', { level: 2, name: 'Thorp 종합 비교' })).toBeTruthy()
    expect(screen.queryByRole('radio', { name: '지금만' })).toBeNull()
    // 종합의 문장은 분야 묶음을 말하므로 지표 칸이 아니라 선택 줄 아래에 선다.
    expect(screen.getByText('실물 경기 흐름은 비슷했습니다. 2019년 비슷한 점.')).toBeTruthy()
    expect(screen.getByText('대외 거래 흐름은 달랐습니다. 2019년 다른 점.')).toBeTruthy()
    expect(screen.getByRole('link', { name: /이 기간으로 백테스트/ }).getAttribute('href')).toBe('/regime-link/start?case=C-1%7E2019-08')
    expect(document.querySelector('a[href^="/regime-search"]')).toBeNull()
    expect(screen.queryByText(/일곱 분야를 함께 견준/)).toBeNull()

    // 일곱 분야(여기서는 둘)의 지표가 분야 이름 아래 선다. 분야 상세와 같은 칸이다.
    await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith('labor', expect.any(AbortSignal)))
    // 묶음 머리는 분야 이름과 대상월이다(접근 가능한 이름은 둘이 붙어 읽힌다).
    const production = await screen.findByRole('region', { name: /^생산/ })
    expect(within(production).getByRole('heading', { level: 4, name: '전산업생산' })).toBeTruthy()
    expect(within(screen.getByRole('region', { name: /^노동/ })).getByText('표시할 지표 자료가 없습니다.')).toBeTruthy()
    await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2019-08', { searchId: 'C-1' }, expect.any(AbortSignal)))
    await waitFor(() => expect(screen.getByRole('list', { name: '전산업생산 3개월 경로' }).textContent).toContain('당시 2019-08 2.6 %'))
    expect(screen.getByText('당시 2019.06 ~ 08')).toBeTruthy()
  })

  it('자세히는 고른 사례의 상세를 줄 아래에 펼친다', async () => {
    renderComposite()
    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })

    fireEvent.click(screen.getByRole('button', { name: '자세히' }))
    expect(await screen.findByRole('heading', { level: 4, name: '사례 상세 · 2019-08' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 4, name: '경제 블록별 거리와 기여' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '닫기' }).getAttribute('aria-expanded')).toBe('true')
  })

  it('기준 충족 사례가 없으면 안내와 함께 기준 미충족 참고 사례를 선택 줄에 바로 두고 분야별 탐색 버튼은 두지 않는다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      cases: [],
      counts: { evaluated: 10, eligible: 3, stored: 0, displayed: 0 },
      thresholdNote: '설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.',
      referenceLabel: '기준 미충족 참고 사례',
      referenceCases: cases(4),
    }))
    renderComposite()

    expect(await screen.findByText(/설정된 종합 유사성 기준을 충족하는 사례가 없습니다/)).toBeTruthy()
    expect(screen.getByText('Thorp가 정한 유사성 기준을 넘지 못한, 가까운 순서의 참고 사례입니다.')).toBeTruthy()
    const nearest = screen.getByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례, 기준 미충족 참고 사례' })
    expect((nearest as HTMLInputElement).checked).toBe(true)
    expect(screen.getAllByRole('radio')).toHaveLength(4)
    expect(screen.queryByRole('button', { name: '분야별로 살펴보기' })).toBeNull()
    expect(screen.queryByRole('link', { name: /참고 사례 보기/ })).toBeNull()
    expect(screen.queryByRole('checkbox', { name: /참고 사례도 함께/ })).toBeNull()
  })

  it('충족 사례가 있으면 참고 사례는 따로 켜야 선택 줄에 선다 (010 FR-021)', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      referenceLabel: '기준 미충족 참고 사례',
      referenceCases: cases(2, 2010),
    }))
    renderComposite()

    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })
    expect(screen.getAllByRole('radio')).toHaveLength(1)
    fireEvent.click(screen.getByRole('checkbox', { name: '기준 미충족 참고 사례도 함께 고르기' }))
    expect(screen.getAllByRole('radio')).toHaveLength(3)
    expect(screen.getByRole('radio', { name: '2010-06 ~ 2010-08, 기준 미충족 참고 사례' })).toBeTruthy()
  })

  it('서버가 실패 사유를 주면 그 문장을 그대로 보이고, 지금의 지표 그래프는 그대로 보인다', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 404, data: { isSuccess: false, message: '아직 계산된 기준월이 없습니다.' } },
    })
    renderComposite()

    expect(await screen.findByText('아직 계산된 기준월이 없습니다.')).toBeTruthy()
    expect(screen.queryByText('과거 사례를 불러오지 못했습니다.')).toBeNull()
    expect(await screen.findByRole('heading', { level: 4, name: '전산업생산' })).toBeTruthy()
  })

  it('받지 못해도 경고로 막지 않고 다시 시도를 둔다', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce(new Error('offline'))
    renderComposite()

    expect(await screen.findByText('과거 사례를 불러오지 못했습니다.')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })).toBeTruthy()
  })

  it('바닥 각주에 검색 상태·역사 비교·자료 기준 시점·더 최신 발표를 짧은 줄로 늘 펼쳐 둔다 (010 FR-031·FR-037)', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: true, sampleRegistry: false },
    }))
    renderComposite()
    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })

    const footnotes = screen.getByLabelText('종합 2026-07 검색 결과: 신뢰 범위와 자료 기준')
    const rows = within(footnotes).getAllByRole('term').map((term) => term.textContent)
    expect(rows).toEqual(['검색 상태', '예시 자료', '역사 비교', '자료 기준 시점', '더 최신인 개별 발표'])
    expect(footnotes.textContent).toContain('이 결과는 아직 확정되지 않은 예시 자료로 계산되었습니다.')
    // 같은 대상월·발표일의 지표는 한 줄에 모은다.
    expect(within(footnotes).getByText('수출 · 수입')).toBeTruthy()
    expect(within(footnotes).getAllByText('대상월 2026-07 · 발표일 2026-09-22')).toHaveLength(1)
    expect(within(footnotes).getByText('소비자물가')).toBeTruthy()
  })

  it('기준월을 지정하면 그 달로 종합 검색을 다시 열고, 최신 기준월로 돌아갈 수 있다', async () => {
    renderComposite()
    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })
    vi.mocked(getRegimeSearch).mockImplementation(async (query) => (
      query.baselineMonth === '2015-03'
        ? compositeResult({ searchId: 'C-2015', baselineMonth: '2015-03', baselineOrigin: 'USER_SPECIFIED', cases: cases(2, 2008) })
        : compositeResult()
    ))

    const input = screen.getByLabelText('기준월 지정') as HTMLInputElement
    expect(input.getAttribute('max')).toBe('2026-07')
    fireEvent.change(input, { target: { value: '2015-03' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'composite', baselineMonth: '2015-03', limit: 10 }, expect.any(AbortSignal)))
    expect(await screen.findByText('기준월을 2015-03로 바꾸어 새 검색을 열었습니다.')).toBeTruthy()
    expect(await screen.findByRole('radio', { name: '2008-06 ~ 2008-08, 가장 가까운 사례' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: '최신 기준월로' }))
    expect(screen.getByText('기준월을 2026-07로 바꾸어 새 검색을 열었습니다.')).toBeTruthy()
    expect(await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '최신 기준월로' })).toBeNull()
  })

  it('최신 계산월보다 뒤의 달을 지정하면 최신 계산월로 물러나고 따로 조회하지 않는다 (010 FR-031)', async () => {
    renderComposite()
    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })

    const input = screen.getByLabelText('기준월 지정') as HTMLInputElement
    fireEvent.change(input, { target: { value: '2026-12' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    expect(input.value).toBe('2026-07')
    expect(getRegimeSearch).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '최신 기준월로' })).toBeNull()
  })

  it('로그인 사용자가 기준월을 지정해 연 검색만 내 기록에 남긴다 (010 FR-032)', async () => {
    vi.mocked(recordRegimeSearch).mockResolvedValue({} as never)
    renderComposite({ id: 7, email: 'u@example.com', nickname: '부캉이' } as unknown as AuthContextValue['user'])
    await screen.findByRole('radio', { name: '2019-06 ~ 2019-08, 가장 가까운 사례' })
    expect(recordRegimeSearch).not.toHaveBeenCalled()
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({ searchId: 'C-2015', baselineMonth: '2015-03', baselineOrigin: 'USER_SPECIFIED' }))

    fireEvent.change(screen.getByLabelText('기준월 지정'), { target: { value: '2015-03' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    await waitFor(() => expect(recordRegimeSearch).toHaveBeenCalledWith('C-2015', 'MARKET_FLOW', 'USER_SPECIFIED'))
    expect(recordRegimeSearch).toHaveBeenCalledTimes(1)
  })
})
