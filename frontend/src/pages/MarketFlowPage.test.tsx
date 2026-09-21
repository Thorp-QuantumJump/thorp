import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { fetchMarketIndicatorsFor } from '@/api/market'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import { fetchSectorCatalog } from '@/api/sectors'
import { MARKET_FLOW_SECTOR_KEY } from '@/marketflow/sectorLink'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { FieldSummary } from '@/types/marketFlow'
import { MarketFlowPage } from './MarketFlowPage'

vi.mock('@/api/marketFlow', () => ({ fetchMarketFlowFields: vi.fn(), fetchMarketFlowField: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))
vi.mock('@/api/sectors', () => ({ fetchSectorCatalog: vi.fn() }))
vi.mock('@/api/market', () => ({ fetchMarketIndicatorsFor: vi.fn() }))
vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn() }))

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
      for (const key of ['Tab', 'Enter', 'ArrowLeft']) {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
        buttons[2].dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
      }
      expect(document.activeElement).toBe(buttons[2])
    })
  })
  describe('섹터 선택과 경제환경 표시의 분리 (SC-004, FR-016)', () => {
    async function renderWithSectors() {
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
      const view = renderWithRouter(<MarketFlowPage />)
      await screen.findAllByRole('button', { name: /분야 열기/ })
      await screen.findByRole('button', { name: '반도체' })
      return view
    }

    /** 분야 목록과 상세가 그려낸 글자 전부다. 한 글자라도 달라지면 SC-004가 깨진 것이다. */
    function economicText(container: HTMLElement): string {
      const rail = container.querySelector('section[aria-labelledby="field-list-title"]')
      const detail = container.querySelector('#market-flow-detail')
      return `${rail?.textContent ?? ''}||${detail?.textContent ?? ''}`
    }

    it('섹터를 골라도 분야 요약과 상세가 그대로다', async () => {
      const { container } = await renderWithSectors()
      const before = economicText(container)

      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() => expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true'))

      expect(economicText(container)).toBe(before)
    })

    /**
     * 표시가 같은 것만으로는 부족하다. 섹터를 요청에 실으면 **같은 값을 다시 받아**
     * 표시가 같아 보일 수 있다. 그래서 조회가 아예 다시 일어나지 않는 것까지 본다.
     */
    it('섹터를 골라도 분야 요약·상세를 다시 조회하지 않는다', async () => {
      await renderWithSectors()
      const summaryCalls = vi.mocked(fetchMarketFlowFields).mock.calls.length
      const detailCalls = vi.mocked(fetchMarketFlowField).mock.calls.length

      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() => expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true'))

      expect(vi.mocked(fetchMarketFlowFields).mock.calls.length).toBe(summaryCalls)
      expect(vi.mocked(fetchMarketFlowField).mock.calls.length).toBe(detailCalls)
    })

    it('분야 조회 인자에 섹터가 실리지 않는다', async () => {
      await renderWithSectors()
      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() => expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBe('SEMICONDUCTOR'))

      expect(fetchMarketFlowFields).toHaveBeenCalledWith(expect.any(AbortSignal))
      for (const call of vi.mocked(fetchMarketFlowField).mock.calls) {
        expect(call).toEqual([expect.any(String), expect.any(AbortSignal)])
      }
    })

    /** FR-016: 섹터를 골랐어도 010으로 넘기는 분야·기준월은 달라지지 않는다. */
    it('섹터를 골라도 비슷했던 때 보기로 넘기는 값이 그대로다', async () => {
      await renderWithSectors()
      const handoffBefore = screen
        .getByRole('button', { name: /비슷했던 때 보기/ })
        .getAttribute('data-handoff')

      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() => expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true'))

      const handoffAfter = screen
        .getByRole('button', { name: /비슷했던 때 보기/ })
        .getAttribute('data-handoff')
      expect(handoffAfter).toBe(handoffBefore)
      expect(JSON.parse(handoffAfter ?? 'null')).not.toHaveProperty('sectorCode')
    })

    // 사례 없이 011을 열지 않는다(FR-018). 이 화면은 사례를 고르는 010으로만 안내한다.
    it('사례가 없으면 전략 비교로 넘길 값을 만들지 않고 사례 고르기로 안내한다', async () => {
      await renderWithSectors()
      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() => expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true'))

      const link = screen.getByRole('link', { name: /과거 사례 고르러 가기/ })
      expect(link.getAttribute('data-handoff')).toBeNull()
      expect(link.getAttribute('href')).toBe('/regime-search')
      expect(screen.getByText(/사례를 먼저 고릅니다/)).toBeTruthy()
      // 011 주소를 이 화면에서 직접 열지 않는다.
      expect(document.body.innerHTML).not.toContain('/regime-link')
    })

    // FR-017: 참고 정보와 뉴스는 분야 요약보다 늦게 채워질 수 있고 요약 표시를 막지 않는다.
    it('국내 시장 참고 정보를 못 얻어도 분야 요약은 그대로 보인다', async () => {
      vi.mocked(fetchMarketIndicatorsFor).mockRejectedValue(new Error('원천 인증 거부'))
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
      renderWithRouter(<MarketFlowPage />)

      for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    })

    // SC-007: 뉴스가 없거나 늦어도 분야 요약 표시를 막지 않는다(FR-019).
    it('뉴스를 못 얻어도 분야 요약은 그대로 보인다', async () => {
      vi.mocked(fetchMarketNewsDigests).mockRejectedValue(new Error('뉴스원 장애'))
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
      renderWithRouter(<MarketFlowPage />)

      for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    })

    it('섹터를 고르면 뉴스만 그 섹터로 다시 조회한다', async () => {
      await renderWithSectors()
      const summaryCalls = vi.mocked(fetchMarketFlowFields).mock.calls.length

      fireEvent.click(screen.getByRole('button', { name: '반도체' }))
      await waitFor(() =>
        expect(fetchMarketNewsDigests).toHaveBeenLastCalledWith('SEMICONDUCTOR', expect.any(AbortSignal)),
      )

      // 뉴스만 좁혀지고 경제환경 자료는 건드리지 않는다(FR-016).
      expect(vi.mocked(fetchMarketFlowFields).mock.calls.length).toBe(summaryCalls)
    })

    it('섹터 목록을 못 얻어도 분야 요약은 그대로 보인다', async () => {
      vi.mocked(fetchSectorCatalog).mockResolvedValue({
        status: 'failed', kind: 'network', code: null,
        message: '서버에 연결할 수 없습니다.', retryable: true,
      })
      vi.mocked(fetchMarketFlowFields).mockResolvedValue({ registryVersion: 'r1', datasetVersion: 'd1', narrativeRuleVersion: 'n1', historicalComparisonNotice: '개정자료 안내', fields })
      vi.mocked(fetchMarketFlowField).mockResolvedValue({ ...fields[0], multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [] })
      renderWithRouter(<MarketFlowPage />)

      for (const name of fieldNames) expect(await screen.findByRole('heading', { name })).toBeTruthy()
    })
  })
})
