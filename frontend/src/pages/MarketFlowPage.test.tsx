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
})
