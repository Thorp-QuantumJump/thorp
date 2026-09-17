import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { FieldSummary } from '@/types/marketFlow'
import { MarketFlowPage } from './MarketFlowPage'

vi.mock('@/api/marketFlow', () => ({ fetchMarketFlowFields: vi.fn(), fetchMarketFlowField: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

const fieldNames = ['생산', '소비·투자', '대외거래', '노동', '물가·원자재', '금융·주택', '해외 경기']
const fields: FieldSummary[] = fieldNames.map((displayName, index) => ({
  fieldId: ['production', 'consumption_investment', 'external_trade', 'labor', 'prices_commodities', 'finance_housing', 'overseas'][index] as FieldSummary['fieldId'],
  displayName, displayOrder: index + 1, status: 'READY', narrative: `${displayName} 변화`,
  narrativeSubjects: [], referenceMonth: '2026-07', referenceMonthHeldBackBy: [],
  lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false,
}))

describe('MarketFlowPage', () => {
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
})
