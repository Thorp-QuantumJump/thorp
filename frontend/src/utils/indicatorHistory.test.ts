import assert from 'node:assert/strict'
import { test } from 'node:test'
import { selectIndicatorPeriod } from './indicatorHistory.ts'

test('기준월 포함 3개월을 정렬하고 원본은 변경하지 않는다', () => {
  const series = [
    { date: '2026-02', value: 102 },
    { date: '2025-11', value: 99 },
    { date: '2026-01', value: 101 },
    { date: '2025-12', value: 100 },
  ]
  const original = structuredClone(series)
  assert.deepEqual(selectIndicatorPeriod(series, '2026-02', 3).map((point) => point.date),
    ['2025-12', '2026-01', '2026-02'])
  assert.deepEqual(series, original)
})

test('잘못된 기준일은 빈 배열을 반환한다', () => {
  const series = [{ date: '2026-12', value: 100 }]
  for (const date of ['', 'broken', '2026', '2026-1', '2026-00', '2026-13',
    '0000-01', '2026-01-00', '2026-02-30', '2026-04-31', '2026-01-01T00:00:00Z', ' 2026-01']) {
    assert.deepEqual(selectIndicatorPeriod(series, date, 3), [], date)
  }
})

test('윤년의 2월 29일은 허용하고 평년·세기 예외는 거부한다', () => {
  for (const [date, valid] of [
    ['2024-02-29', true], ['2000-02-29', true],
    ['2026-02-29', false], ['1900-02-29', false], ['2100-02-29', false],
  ] as const) {
    const series = [{ date, value: 100 }]
    assert.equal(selectIndicatorPeriod(series, date, 3).length, valid ? 1 : 0, date)
  }
})

test('개별 날짜가 잘못되거나 값이 유한하지 않은 데이터는 제외한다', () => {
  const series = [
    { date: '2026-02-28', value: 100 },
    { date: '2026-02-30', value: 101 },
    { date: '2026-13', value: 102 },
    { date: 'bad', value: 103 },
    { date: '2026-02-26', value: NaN },
    { date: '2026-02-27', value: Infinity },
  ]
  assert.deepEqual(selectIndicatorPeriod(series, '2026-03-01', 3), [series[0]])
})

test('일간 기준일 뒤의 값은 제외한다', () => {
  const series = [
    { date: '2026-02-01', value: 100 },
    { date: '2026-02-15', value: 101 },
    { date: '2026-02-16', value: 102 },
    { date: '2026-03-01', value: 103 },
  ]
  assert.deepEqual(selectIndicatorPeriod(series, '2026-02-15', 3), series.slice(0, 2))
})

test('월간 기준일은 해당 월의 마지막 날까지 포함한다', () => {
  const series = [{ date: '2026-02-28', value: 100 }, { date: '2026-03-01', value: 101 }]
  assert.deepEqual(selectIndicatorPeriod(series, '2026-02', 3), [series[0]])
})

test('빈 데이터와 잘못된 기간은 빈 배열을 반환한다', () => {
  assert.deepEqual(selectIndicatorPeriod([], '2026-02', 3), [])
  for (const months of [0, -1, 1.5, NaN, Infinity]) {
    assert.deepEqual(selectIndicatorPeriod([{ date: '2026-02', value: 100 }], '2026-02', months), [])
  }
})

test('5년 조회는 기준월부터 60개월만 포함한다', () => {
  const series = Array.from({ length: 61 }, (_, index) => ({
    date: new Date(Date.UTC(2021, 7 + index, 1)).toISOString().slice(0, 7),
    value: index,
  }))
  const result = selectIndicatorPeriod(series, '2026-08', 60)
  assert.equal(result.length, 60)
  assert.equal(result[0].date, '2021-09')
  assert.equal(result.at(-1)?.date, '2026-08')
})
