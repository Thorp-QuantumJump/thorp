import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { fetchMarketIndicatorsFor } from '@/api/market'
import { fetchMarketNewsDigests, fetchNewsSectors } from '@/api/marketNews'
import { fetchSectorCatalog } from '@/api/sectors'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { FieldSummary } from '@/types/marketFlow'
import { MarketFlowPage } from './MarketFlowPage'

vi.mock('@/api/marketFlow', () => ({ fetchMarketFlowFields: vi.fn(), fetchMarketFlowField: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))
vi.mock('@/api/sectors', () => ({ fetchSectorCatalog: vi.fn() }))
vi.mock('@/api/market', () => ({ fetchMarketIndicatorsFor: vi.fn() }))
vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn(), fetchNewsSectors: vi.fn() }))

vi.mock('@/api/regime', () => ({ getRegimeBaselineMonths: vi.fn().mockResolvedValue({ byField: [] }), getRegimeSearch: vi.fn() }))

const sectorCatalog = {
  status: 'loaded' as const,
  catalog: {
    mapVersion: 'map-v1',
    isSample: false,
    sectors: [
      {
        code: 'SEMICONDUCTOR',
        name: '반도체',
        classification: 'INDUSTRY' as const,
        representativeEtf: {
          instrumentId: 'SEMI-ETF', name: '반도체 ETF',
          listedOn: '2015-03-02', benchmarkIndex: 'KRX 반도체',
        },
      },
    ],
  },
}

const fieldNames = ['생산', '소비·투자', '대외거래', '노동', '물가·원자재', '금융·주택', '해외 경기']
const fields: FieldSummary[] = fieldNames.map((displayName, index) => ({
  fieldId: ['production', 'consumption_investment', 'external_trade', 'labor', 'prices_commodities', 'finance_housing', 'overseas'][index] as FieldSummary['fieldId'],
  displayName, displayOrder: index + 1, status: 'READY', narrative: `${displayName} 변화`,
  narrativeSubjects: [], referenceMonth: '2026-07', referenceMonthHeldBackBy: [],
  lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false,
}))

describe('MarketFlowPage', () => {
  beforeEach(() => {
    vi.mocked(fetchSectorCatalog).mockResolvedValue(sectorCatalog)
    // 참고 정보는 늦거나 빠질 수 있다. 기본값은 '없음'으로 두고 필요한 테스트만 채운다.
    vi.mocked(fetchMarketIndicatorsFor).mockResolvedValue({ data: {}, errors: {} })
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue({
      context: 'CURRENT', state: 'EMPTY', sectorId: null, from: null, to: null,
      lastUpdatedAt: null, stale: false, statusMessage: null, digests: [],
    })
    vi.mocked(fetchNewsSectors).mockResolvedValue([])
    window.sessionStorage.clear()
  })

  it('로그인 없이 일곱 분야를 먼저 보여 주고 전체 경제 판정을 만들지 않는다', async () => {
    vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '최신 개정자료를 이용한 역사 비교입니다.', fields })
    // 화면은 첫 분야 상세를 함께 연다. 이 호출을 두지 않으면 요약 검증 전에 화면이 먼저 무너진다.
    vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
    renderWithRouter(<MarketFlowPage />)

    for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/경제 전체.*(점수|등급)|(점수|등급).*경제 전체/)
  })

  it('분야 열기를 누르면 해당 상세를 조회한다', async () => {
    vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
    vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
    renderWithRouter(<MarketFlowPage />)
    const buttons = await screen.findAllByRole('button', { name: /분야 열기/ })
    // 첫 분야는 화면이 스스로 열어 두므로, 누른 효과는 다른 분야로 확인한다.
    fireEvent.click(buttons[3])
    await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith('labor', expect.any(AbortSignal)))
  })

  describe('분야 목록 키보드 이동', () => {
    async function renderFieldButtons() {
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockImplementation(async (fieldId) => ({
        ...fields.find((item) => item.fieldId === fieldId)!,
        multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.',
        indicators: [],
      }))
      renderWithRouter(<MarketFlowPage />)
      return await screen.findAllByRole('button', { name: /분야 열기/ })
    }

    /**
     * 목록은 화살표로 옮겨 다니는 한 벌이라 Tab 정지점이 일곱 개로 늘어나지 않는다.
     * 그래서 포커스만 옮기고 끝내면 안 되고, 옮긴 분야가 실제로 열려야 한다.
     */
    it.each([
      ['ArrowDown', 0, 1, 'consumption_investment'],
      ['ArrowUp', 2, 1, 'consumption_investment'],
      // 메뉴가 가로 한 줄이라 좌우 화살표도 같은 일을 한다.
      ['ArrowRight', 0, 1, 'consumption_investment'],
      ['ArrowLeft', 2, 1, 'consumption_investment'],
      ['Home', 4, 0, 'production'],
      ['End', 0, 6, 'overseas'],
    ])('%s는 %i번에서 %i번 분야로 포커스와 선택을 함께 옮긴다', async (key, from, to, expectedId) => {
      const buttons = await renderFieldButtons()
      buttons[from].focus()

      fireEvent.keyDown(buttons[from], { key })

      expect(document.activeElement).toBe(buttons[to])
      await waitFor(() => expect(fetchMarketFlowField).toHaveBeenCalledWith(expectedId, expect.any(AbortSignal)))
      expect(buttons[to].getAttribute('aria-pressed')).toBe('true')
    })

    it.each([
      ['ArrowUp', 0, 6],
      ['ArrowDown', 6, 0],
    ])('%s는 끝에서 반대쪽 끝으로 돈다', async (key, from, to) => {
      const buttons = await renderFieldButtons()
      buttons[from].focus()

      fireEvent.keyDown(buttons[from], { key })

      expect(document.activeElement).toBe(buttons[to])
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
  it('분야 자료가 실패해도 과거 사례와 뉴스 카드를 유지한다', async () => {
    vi.mocked(fetchMarketFlowFields).mockRejectedValue(new Error('failed'))
    renderWithRouter(<MarketFlowPage />)
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '지금과 비슷했던 과거 시장' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '뉴스' })).toBeTruthy()
    expect(fetchMarketNewsDigests).toHaveBeenCalled()
  })

  it('과거 사례·뉴스를 화면 머리 바로 아래 두고, 분야 메뉴는 구역 제목과 함께 그 아래 둔다', async () => {
    vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
    vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
    renderWithRouter(<MarketFlowPage />)
    const [firstField] = await screen.findAllByRole('button', { name: /분야 열기/ })

    const pageTitle = screen.getByRole('heading', { level: 1, name: '지금 시장과 닮은 과거를 찾아보세요.' })
    const pastTitle = screen.getByRole('heading', { name: '지금과 비슷했던 과거 시장' })
    const newsTitle = screen.getByRole('heading', { name: '뉴스' })
    const fieldTitle = screen.getByRole('heading', { level: 2, name: '시장 흐름을 분야별로 확인하세요.' })
    const follows = (earlier: Node, later: Node) => Boolean(earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING)
    expect(follows(pageTitle, pastTitle)).toBe(true)
    expect(follows(newsTitle, fieldTitle)).toBe(true)
    expect(follows(fieldTitle, firstField)).toBe(true)
    // 분야 구역은 보이는 구역 제목으로 이름을 얻는다.
    expect(screen.getByRole('region', { name: '시장 흐름을 분야별로 확인하세요.' }).contains(firstField)).toBe(true)
  })

  describe('시장 흐름의 과거 사례와 뉴스', () => {
    it('과거 사례와 뉴스를 보이고 투자 대상과 국내 시장 참고 정보는 옮기지 않는다', async () => {
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
      renderWithRouter(<MarketFlowPage />)

      for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
      expect(screen.queryByRole('heading', { name: '섹터 좁혀 보기' })).toBeNull()
      expect(screen.queryByRole('heading', { name: '국내 시장 참고 정보' })).toBeNull()
      expect(screen.getByRole('heading', { name: '뉴스' })).toBeTruthy()
      expect(screen.getByRole('heading', { name: '지금과 비슷했던 과거 시장' })).toBeTruthy()
      expect(screen.queryByRole('link', { name: /과거 사례 고르러 가기/ })).toBeNull()
      // 뉴스만 독립적으로 조회한다.
      expect(fetchSectorCatalog).not.toHaveBeenCalled()
      expect(fetchMarketIndicatorsFor).not.toHaveBeenCalled()
      expect(fetchMarketNewsDigests).toHaveBeenCalled()
    })
  })
})
