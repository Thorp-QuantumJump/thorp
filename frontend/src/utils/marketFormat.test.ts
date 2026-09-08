import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Indicator } from '@/types/market'
import { formatIndicatorValue } from './marketFormat.ts'

test('월간 수출액을 10억 달러 단위로 축약한다', () => {
  const indicator = { value: 61_200_000_000, unit: 'USD', decimals: 0 } as Indicator
  assert.equal(formatIndicatorValue(indicator), '61.2B USD')
})

test('M2 십억원 값을 조원 단위로 축약한다', () => {
  const indicator = { value: 4_200_000, unit: '십억원', decimals: 0 } as Indicator
  assert.equal(formatIndicatorValue(indicator), '4,200.0 조원')
})
