import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { fetchMarketIndicatorsFor } from '@/api/market'
import { fetchMarketNewsDigests, fetchNewsSectors } from '@/api/marketNews'
import { getRegimeCaseDetail, getRegimeSearch } from '@/api/regime'
import { fetchSectorCatalog } from '@/api/sectors'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { FieldDetailResult, FieldSummary } from '@/types/marketFlow'
import type { RegimeCaseDetail, RegimeSearchResult } from '@/types/regime'
import { MarketFlowPage } from './MarketFlowPage'

vi.mock('@/api/marketFlow', () => ({ fetchMarketFlowFields: vi.fn(), fetchMarketFlowField: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))
vi.mock('@/api/sectors', () => ({ fetchSectorCatalog: vi.fn() }))
vi.mock('@/api/market', () => ({ fetchMarketIndicatorsFor: vi.fn() }))
vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn(), fetchNewsSectors: vi.fn() }))
vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))

const fieldNames = ['생산', '소비·투자', '대외거래', '노동', '물가·원자재', '금융·주택', '해외 경기']
const fields: FieldSummary[] = fieldNames.map((displayName, index) => ({
  fieldId: ['production', 'consumption_investment', 'external_trade', 'labor', 'prices_commodities', 'finance_housing', 'overseas'][index] as FieldSummary['fieldId'],
  displayName, displayOrder: index + 1, status: 'READY', narrative: `${displayName} 변화`,
  narrativeSubjects: [], referenceMonth: '2026-07', referenceMonthHeldBackBy: [],
  lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false,
}))

/** 생산 분야 상세. 지표 둘은 010 사례 상세와 같은 id를 쓴다. */
const productionDetail: FieldDetailResult = {
  ...fields[0],
  multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.',
  indicators: [
    {
      indicatorId: 'all_industry_production', name: '전산업생산', unit: '%', value: 3.1,
      path: [{ month: '2026-05', value: 1.9 }, { month: '2026-06', value: 4.4 }, { month: '2026-07', value: 3.1 }],
      referenceMonth: '2026-07', releaseDate: null, source: '국가통계포털(KOSIS)', agency: '통계청', afterReferenceMonthRelease: null,
    },
    {
      indicatorId: 'service_production', name: '서비스업생산', unit: '%', value: 7.3,
      path: [{ month: '2026-05', value: 8.6 }, { month: '2026-06', value: 9.4 }, { month: '2026-07', value: 7.3 }],
      referenceMonth: '2026-07', releaseDate: null, source: '국가통계포털(KOSIS)', agency: '통계청', afterReferenceMonthRelease: null,
    },
  ],
}

const fieldSearch = {
  searchId: 'S-1', mode: 'FIELD', field: { id: 'production', name: '생산' }, baselineMonth: '2026-07',
  status: 'NEAREST_ONLY', statusNote: '가까운 순서만 제공합니다.', caseLabel: '최근접 참고 사례',
  // api/regime.ts의 응답 검사가 요구하는 칸이다. 실제 응답은 늘 이 칸을 싣는다.
  versions: { data: 'macro-1', registry: 'calc-1', model: 'REGIME-V1.1', revision: 1, sampleData: false, sampleRegistry: false },
  comparisonMonths: [],
  dataAsOf: { indicators: [], note: '생산 지표는 2026년 7월분까지 반영되어 있습니다.', revisions: [], newerReleases: [] },
  cases: [
    { endMonth: '2001-03', rank: 1, observationMonths: ['2001-01', '2001-02', '2001-03'], distance: '0.37', similar: ['전산업생산은 3.1%로 당시 2.6%와 비슷합니다.'], different: ['서비스업생산은 지금이 7.3%로 당시 5.9%보다 높습니다.'], detailAvailable: true },
    { endMonth: '2011-10', rank: 2, observationMonths: ['2011-08', '2011-09', '2011-10'], distance: '0.45', similar: [], different: [], detailAvailable: true },
  ],
} as unknown as RegimeSearchResult

function caseDetail(endMonth: string, caseMonths: string[], raw: [string[], string[]]): RegimeCaseDetail {
  const scale = { median: '2', spread: '3', method: 'MAD', windowStart: '2016-05', windowEnd: '2026-04', months: 120 }
  return {
    searchId: 'S-1', endMonth, observationMonths: caseMonths, distance: '0.37', byField: {}, eraPosition: null,
    indicators: [
      { id: 'all_industry_production', name: '전산업생산', unit: '%', comparisonMonths: ['2026-05', '2026-06', '2026-07'], caseMonths, queryRaw: [], caseRaw: raw[0], queryZ: [], caseZ: [], e: '0.2', nominalWeight: '0.33', scale },
      { id: 'service_production', name: '서비스업생산', unit: '%', comparisonMonths: ['2026-05', '2026-06', '2026-07'], caseMonths, queryRaw: [], caseRaw: raw[1], queryZ: [], caseZ: [], e: '0.6', nominalWeight: '0.33', scale },
    ],
  } as unknown as RegimeCaseDetail
}

function mockFields() {
  vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '최신 개정자료를 이용한 역사 비교입니다.', fields })
  vi.mocked(fetchMarketFlowField).mockImplementation(async (fieldId) => (
    fieldId === 'production' ? productionDetail : { ...fields.find((item) => item.fieldId === fieldId)!, multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] }
  ))
}

describe('MarketFlowPage', () => {
  beforeEach(() => {
    vi.mocked(fetchMarketNewsDigests).mockReset()
    vi.mocked(fetchNewsSectors).mockReset()
    vi.mocked(getRegimeSearch).mockReset()
    vi.mocked(getRegimeSearch).mockResolvedValue(fieldSearch)
    vi.mocked(getRegimeCaseDetail).mockReset()
    vi.mocked(getRegimeCaseDetail).mockImplementation(async (endMonth) => endMonth === '2001-03'
      ? caseDetail('2001-03', ['2001-01', '2001-02', '2001-03'], [['2.5', '5.0', '2.6'], ['8.5', '7.6', '5.9']])
      : caseDetail('2011-10', ['2011-08', '2011-09', '2011-10'], [['3.9', '4.9', '3.5'], ['8.6', '7.2', '6.6']]))
    window.sessionStorage.clear()
  })

  it('로그인 없이 일곱 분야를 먼저 보여 주고 전체 경제 판정을 만들지 않는다', async () => {
    mockFields()
    renderWithRouter(<MarketFlowPage />)

    for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: '시장 흐름을 분야별로 확인하세요.' })).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/경제 전체.*(점수|등급)|(점수|등급).*경제 전체/)
  })

  it('분야 열기를 누르면 해당 상세와 그 분야의 과거 사례를 조회한다', async () => {
    mockFields()
    renderWithRouter(<MarketFlowPage />)
    const buttons = await screen.findAllByRole('button', { name: /분야 열기/ })
    // 첫 분야는 화면이 스스로 열어 두므로, 누른 효과는 다른 분야로 확인한다.
    fireEvent.click(buttons[3])
    await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith('labor', expect.any(AbortSignal)))
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'field', field: 'labor', limit: 3 }, expect.any(AbortSignal)))
  })

  describe('분야 상세 안의 지금과 비슷했던 과거 (009 FR-019)', () => {
    it('비교 줄을 분야 상세 안에 두고, 가장 가까운 사례의 당시 경로를 같은 지표 그래프에 겹친다', async () => {
      mockFields()
      renderWithRouter(<MarketFlowPage />)

      const compareTitle = await screen.findByRole('heading', { name: '지금과 비슷했던 과거' })
      const detail = document.getElementById('market-flow-detail') as HTMLElement
      expect(detail.contains(compareTitle)).toBe(true)
      const firstIndicator = within(detail).getByRole('heading', { level: 3, name: '전산업생산' })
      expect(compareTitle.compareDocumentPosition(firstIndicator) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

      const nearest = await screen.findByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })
      expect((nearest as HTMLInputElement).checked).toBe(true)
      await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2001-03', { searchId: 'S-1' }, expect.any(AbortSignal)))
      await waitFor(() => expect(screen.getByRole('list', { name: '전산업생산 3개월 경로' }).textContent).toContain('당시 2001-03 2.6 %'))

      // 비슷한 점·다른 점은 그 문장이 다루는 지표 칸에 붙는다.
      const allIndustry = firstIndicator.closest('article') as HTMLElement
      const service = within(detail).getByRole('heading', { level: 3, name: '서비스업생산' }).closest('article') as HTMLElement
      expect(within(allIndustry).getByText('비슷한 점')).toBeTruthy()
      expect(within(service).getByText('다른 점')).toBeTruthy()
      expect(screen.getByRole('link', { name: /이 기간으로 백테스트/ }).getAttribute('href')).toBe('/regime-link/start?case=S-1%7E2001-03')
    })

    it('지금만을 고르면 겹친 경로와 문장을 걷고, 다른 사례를 고르면 그 사례의 상세를 받는다', async () => {
      mockFields()
      renderWithRouter(<MarketFlowPage />)
      await screen.findByText('비슷한 점')

      fireEvent.click(screen.getByRole('radio', { name: '지금만' }))
      expect(screen.queryByText('비슷한 점')).toBeNull()
      expect(screen.getByRole('list', { name: '전산업생산 3개월 경로' }).textContent).not.toContain('당시')
      expect(screen.getByRole('button', { name: /이 기간으로 백테스트/ })).toHaveProperty('disabled', true)

      fireEvent.click(screen.getByRole('radio', { name: '2011-08 ~ 2011-10' }))
      await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2011-10', { searchId: 'S-1' }, expect.any(AbortSignal)))
      await waitFor(() => expect(screen.getByRole('list', { name: '전산업생산 3개월 경로' }).textContent).toContain('당시 2011-10 3.5 %'))
      // 이 사례에는 문장이 없어 지금 값과 당시 값만 적는다.
      expect(screen.queryByText('비슷한 점')).toBeNull()
      expect(screen.getByText(/지금 3\.1% · 당시/).textContent).toBe('지금 3.1% · 당시 3.5%')
    })

    it('사례를 골라도 라디오의 초점이 그대로이고, 010 검색은 분야와 010 기준월로 연다', async () => {
      mockFields()
      renderWithRouter(<MarketFlowPage />)
      const second = await screen.findByRole('radio', { name: '2011-08 ~ 2011-10' })
      second.focus()

      fireEvent.click(second)
      await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2011-10', { searchId: 'S-1' }, expect.any(AbortSignal)))
      // 다시 그려도 라디오를 새로 만들지 않는다. 새로 만들면 키보드 사용자의 초점이 문서 처음으로 떨어진다.
      expect(document.activeElement).toBe(second)
      expect(screen.getByRole('link', { name: /모두 보기/ }).getAttribute('href')).toBe('/regime-search?field=production&month=2026-07')
    })

    it('보통 범위를 켜면 지표마다 띠를 그리고, 지금만이어도 켜 둘 수 있다', async () => {
      mockFields()
      const { container } = renderWithRouter(<MarketFlowPage />)
      await screen.findByText('비슷한 점')

      fireEvent.click(await screen.findByRole('checkbox', { name: /지난 10년 보통 범위/ }))
      expect(container.querySelectorAll('rect[data-band]')).toHaveLength(2)

      fireEvent.click(screen.getByRole('radio', { name: '지금만' }))
      expect(container.querySelectorAll('path[data-past]')).toHaveLength(0)
      expect(container.querySelectorAll('rect[data-band]')).toHaveLength(2)
    })

    it('분야를 바꾸면 그 분야의 검색 결과로 바뀌고 이전 분야의 사례가 남지 않는다', async () => {
      mockFields()
      const laborSearch = {
        ...fieldSearch, searchId: 'S-labor', field: { id: 'labor', name: '노동' }, baselineMonth: '2026-07',
        cases: [{ endMonth: '2015-06', rank: 1, observationMonths: ['2015-04', '2015-05', '2015-06'], distance: '0.2', similar: [], different: [], detailAvailable: true }],
      } as unknown as RegimeSearchResult
      vi.mocked(getRegimeSearch).mockImplementation(async (query) => (query.field === 'labor' ? laborSearch : fieldSearch))
      renderWithRouter(<MarketFlowPage />)
      await screen.findByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })

      fireEvent.click(screen.getAllByRole('button', { name: /분야 열기/ })[3])

      expect(await screen.findByRole('radio', { name: '2015-04 ~ 2015-06, 가장 가까운 사례' })).toBeTruthy()
      expect(screen.queryByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })).toBeNull()
      await waitFor(() => expect(getRegimeCaseDetail).toHaveBeenCalledWith('2015-06', { searchId: 'S-labor' }, expect.any(AbortSignal)))
    })

    it('과거 사례를 받지 못해도 분야 지표는 그대로 보이고 그 줄에서 다시 시도한다', async () => {
      mockFields()
      vi.mocked(getRegimeSearch).mockRejectedValueOnce(new Error('offline'))
      renderWithRouter(<MarketFlowPage />)

      expect(await screen.findByText(/과거 사례를 불러오지 못했습니다/)).toBeTruthy()
      expect(screen.getByRole('heading', { level: 3, name: '전산업생산' })).toBeTruthy()
      fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
      expect(await screen.findByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })).toBeTruthy()
    })

    it('분야마다 고른 사례가 남고, 종합 비교를 보다 돌아와도 분야 상세를 다시 받지 않는다', async () => {
      mockFields()
      renderWithRouter(<MarketFlowPage />)
      fireEvent.click(await screen.findByRole('radio', { name: '지금만' }))

      fireEvent.click(screen.getAllByRole('button', { name: /분야 열기/ })[3])
      await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith('labor', expect.any(AbortSignal)))
      fireEvent.click(screen.getAllByRole('button', { name: /분야 열기/ })[0])
      // 생산에서 고른 '지금만'이 그대로다. 노동을 지나왔다고 가장 가까운 사례로 돌아가지 않는다.
      expect(((await screen.findByRole('radio', { name: '지금만' })) as HTMLInputElement).checked).toBe(true)

      const productionCalls = () => vi.mocked(fetchMarketFlowField).mock.calls.filter(([id]) => id === 'production').length
      const before = productionCalls()
      fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
      await screen.findByRole('heading', { level: 2, name: 'Thorp 종합 비교' })
      fireEvent.click(screen.getAllByRole('button', { name: /분야 열기/ })[0])
      expect(await screen.findByRole('heading', { level: 3, name: '전산업생산' })).toBeTruthy()
      expect(productionCalls()).toBe(before)
    })

    it("분야 메뉴 끝의 'Thorp 종합 비교'를 누를 때만 종합 검색을 열고 상자에 그 목록을 보인다", async () => {
      mockFields()
      renderWithRouter(<MarketFlowPage />)
      await screen.findByRole('radio', { name: '2001-01 ~ 2001-03, 가장 가까운 사례' })
      expect(vi.mocked(getRegimeSearch).mock.calls.some(([query]) => query.mode === 'composite')).toBe(false)

      fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
      await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'composite', limit: 3 }, expect.any(AbortSignal)))
      expect(await screen.findByRole('heading', { level: 2, name: 'Thorp 종합 비교' })).toBeTruthy()
      expect(screen.queryByRole('heading', { level: 3, name: '전산업생산' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Thorp 종합 비교' }).getAttribute('aria-pressed')).toBe('true')
    })
  })

  describe('분야 목록 키보드 이동', () => {
    async function renderFieldButtons() {
      mockFields()
      renderWithRouter(<MarketFlowPage />)
      return await screen.findAllByRole('button', { name: /분야 열기/ })
    }

    /**
     * 화살표로 옮겨 다니는 한 벌의 메뉴다. 포커스만 옮기고 끝내면 안 되고, 옮긴 분야가 실제로 열려야 한다.
     */
    it.each([
      ['ArrowDown', 0, 1, 'consumption_investment'],
      ['ArrowUp', 2, 1, 'consumption_investment'],
      // 메뉴가 가로 한 줄이라 좌우 화살표도 같은 일을 한다.
      ['ArrowRight', 0, 1, 'consumption_investment'],
      ['ArrowLeft', 2, 1, 'consumption_investment'],
      ['Home', 4, 0, 'production'],
    ])('%s는 %i번에서 %i번 분야로 포커스와 선택을 함께 옮긴다', async (key, from, to, expectedId) => {
      const buttons = await renderFieldButtons()
      buttons[from].focus()

      fireEvent.keyDown(buttons[from], { key })

      expect(document.activeElement).toBe(buttons[to])
      await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith(expectedId, expect.any(AbortSignal)))
      expect(buttons[to].getAttribute('aria-pressed')).toBe('true')
    })

    // 'Thorp 종합 비교'는 같은 메뉴 줄의 끝 칸이라 화살표 묶음에 든다.
    it.each([
      ['End', 0],
      ['ArrowUp', 0],
      ['ArrowLeft', 0],
    ])('%s는 %i번 분야에서 끝의 종합 비교로 간다', async (key, from) => {
      const buttons = await renderFieldButtons()
      buttons[from].focus()

      fireEvent.keyDown(buttons[from], { key })

      const composite = screen.getByRole('button', { name: 'Thorp 종합 비교' })
      expect(document.activeElement).toBe(composite)
      expect(composite.getAttribute('aria-pressed')).toBe('true')
    })

    it('종합 비교에서 다음 칸으로 가면 첫 분야로 돈다', async () => {
      const buttons = await renderFieldButtons()
      const composite = screen.getByRole('button', { name: 'Thorp 종합 비교' })
      fireEvent.click(composite)
      composite.focus()

      fireEvent.keyDown(composite, { key: 'ArrowDown' })

      expect(document.activeElement).toBe(buttons[0])
      expect(buttons[0].getAttribute('aria-pressed')).toBe('true')
    })

    it('목록이 쓰지 않는 키는 가로채지 않는다', async () => {
      const buttons = await renderFieldButtons()
      buttons[2].focus()

      // Tab·Enter는 브라우저와 button 기본 동작에 맡긴다. 여기서 preventDefault하면
      // 키보드 사용자가 목록 밖으로 나가지 못한다.
      for (const key of ['Tab', 'Enter', 'a']) {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
        buttons[2].dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
      }
      expect(document.activeElement).toBe(buttons[2])
    })
  })

  it('분야 자료가 실패하면 알린다. 지금 시장 뉴스는 투자 대상 고르기로 옮겨 이 화면에 두지 않는다', async () => {
    vi.mocked(fetchMarketFlowFields).mockRejectedValue(new Error('failed'))
    renderWithRouter(<MarketFlowPage />)
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: /뉴스/ })).toBeNull()
    expect(fetchMarketNewsDigests).not.toHaveBeenCalled()
    expect(fetchNewsSectors).not.toHaveBeenCalled()
  })

  it('투자 대상과 국내 시장 참고 정보는 옮겨 오지 않는다', async () => {
    mockFields()
    renderWithRouter(<MarketFlowPage />)

    for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '섹터 좁혀 보기' })).toBeNull()
    expect(screen.queryByRole('heading', { name: '국내 시장 참고 정보' })).toBeNull()
    expect(screen.queryByRole('link', { name: /과거 사례 고르러 가기/ })).toBeNull()
    expect(fetchSectorCatalog).not.toHaveBeenCalled()
    expect(fetchMarketIndicatorsFor).not.toHaveBeenCalled()
  })
})
