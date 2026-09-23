import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Indicator, SimilarMarketPeriod } from '@/types/market'
import { withCurrentIndicatorValues } from './marketComparison.ts'

const period = {
  comparisons: [
    { indicatorId: 'live', currentValue: 1, historicalValue: 2, unit: '%' },
    { indicatorId: 'failed', currentValue: 3, historicalValue: 4, unit: 'pt' },
  ],
} as SimilarMarketPeriod

test('API로 확인된 지표만 비교 패널 현재값과 단위를 갱신한다', () => {
  const indicators = [
    { id: 'live', value: 10, unit: '% YoY', dataStatus: 'live' },
    { id: 'failed', value: 30, unit: '지수', dataStatus: 'error' },
  ] as Indicator[]

  const result = withCurrentIndicatorValues(period, indicators)

  assert.deepEqual(result.comparisons[0], {
    indicatorId: 'live',
    currentValue: 10,
    historicalValue: 2,
    unit: '% YoY',
  })
  assert.deepEqual(result.comparisons[1], period.comparisons[1])
  assert.notEqual(result, period)
})
