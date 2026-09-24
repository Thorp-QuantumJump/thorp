import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { FieldDetailResult, FieldSummary, IndicatorDisplay, MarketFlowFieldId } from '@/types/marketFlow'
import { MarketFlowPreview } from './MarketFlowPreview'
import { describeFlowChange, formatFlowValue } from './marketFlowPreviewFormat'

const fieldIds: MarketFlowFieldId[] = [
  'production',
  'consumption_investment',
  'external_trade',
  'labor',
  'prices_commodities',
  'finance_housing',
  'overseas',
]

function summary(fieldId: MarketFlowFieldId, index: number): FieldSummary {
  return {
    fieldId,
    displayName: `분야${index + 1}`,
    displayOrder: index + 1,
    status: 'READY',
    narrative: `분야${index + 1} 요약`,
    narrativeSubjects: [],
    referenceMonth: '2026-08',
    referenceMonthHeldBackBy: [],
    lastUpdatedAt: '2026-09-15T21:10:00Z',
    isExampleData: false,
  }
}

function indicator(indicatorId: string, unit: string, values: [number, number, number]): IndicatorDisplay {
  return {
    indicatorId,
    name: `지표 ${indicatorId}`,
    unit,
    value: values[2],
    path: [
      { month: '2026-06', value: values[0] },
      { month: '2026-07', value: values[1] },
      { month: '2026-08', value: values[2] },
    ],
    referenceMonth: '2026-08',
    releaseDate: '2026-09-10',
    source: '원천',
    agency: '기관',
    afterReferenceMonthRelease: null,
  }
}

/** 17개 지표를 일곱 분야에 나눠 준다(3·3·3·2·2·2·2). */
const perField = [3, 3, 3, 2, 2, 2, 2]

vi.mock('@/api/marketFlow', () => ({
  fetchMarketFlowFields: vi.fn(() =>
    Promise.resolve({
      registryVersion: 'r',
      datasetVersion: 'd',
      narrativeRuleVersion: 'n',
      historicalComparisonNotice: '',
      fields: fieldIds.map(summary),
    }),
  ),
  fetchMarketFlowField: vi.fn((fieldId: MarketFlowFieldId) => {
    const index = fieldIds.indexOf(fieldId)
    const detail: FieldDetailResult = {
      ...summary(fieldId, index),
      multipleReferenceMonthNotice: '',
      indicators: Array.from({ length: perField[index] }, (_, k) =>
        indicator(`${fieldId}-${k}`, '%', [1, 2, k % 2 === 0 ? 3 : 1]),
      ),
    }
    return Promise.resolve(detail)
  }),
}))

describe('MarketFlowPreview', () => {
  it('일곱 분야와 17개 지표를 실제 응답에서 그린다', async () => {
    render(<MarketFlowPreview />)

    const list = await screen.findByRole('list', { name: '일곱 분야 요약' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(7)
    expect(screen.getByLabelText('경제지표 17개 흐름')).toBeDefined()
    expect(screen.getByText('분야1 요약')).toBeDefined()
  })

  it('원천의 셈 단위를 그대로 적지 않는다', () => {
    expect(formatFlowValue(4_200_000, '십억원')).toBe('4,200조 원')
    expect(formatFlowValue(650, '십억원')).toBe('6,500억 원')
    expect(formatFlowValue(6_512_000_000, '미국 달러')).toBe('65.1억 달러')
    expect(formatFlowValue(1378.2, '원')).toBe('1,378원')
    expect(formatFlowValue(3.18, '연%')).toBe('3.18%')
  })

  it('직전 달 대비 방향으로 오르내림을 가른다', () => {
    expect(describeFlowChange(indicator('a', '%', [2.8, 2.8, 2.9]))).toEqual({ text: '+0.1%p', direction: 'up' })
    expect(describeFlowChange(indicator('b', '원', [1300, 1400, 1386]))).toEqual({ text: '−1%', direction: 'down' })
  })
})
