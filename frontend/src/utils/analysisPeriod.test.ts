import { describe, expect, it } from 'vitest'
import {
  addYears,
  checkPeriodInput,
  isIsoDate,
  isWithin,
  meetsMinimumYears,
} from './analysisPeriod'

const range = { start: '2020-01-02', end: '2024-12-30' }

describe('analysisPeriod', () => {
  it('실제로 있는 날짜만 날짜로 본다', () => {
    expect(isIsoDate('2024-02-29')).toBe(true)
    expect(isIsoDate('2023-02-29')).toBe(false)
    expect(isIsoDate('2023-13-01')).toBe(false)
    expect(isIsoDate('2023-1-01')).toBe(false)
    expect(isIsoDate('')).toBe(false)
    expect(isIsoDate(20230101)).toBe(false)
  })

  it('연수는 달력 기준으로 더하고 윤일은 평년의 2월 28일로 옮긴다', () => {
    expect(addYears('2020-01-02', 1)).toBe('2021-01-02')
    expect(addYears('2020-02-29', 1)).toBe('2021-02-28')
    expect(addYears('2020-02-29', 4)).toBe('2024-02-29')
  })

  it('경계와 같은 날은 범위 안이다', () => {
    expect(isWithin(range, range)).toBe(true)
    expect(isWithin({ start: '2020-01-01', end: '2024-12-30' }, range)).toBe(false)
    expect(isWithin({ start: '2020-01-02', end: '2024-12-31' }, range)).toBe(false)
  })

  it('최소 평가 기간은 정확히 N년이면 통과하고 하루라도 모자라면 막는다', () => {
    expect(meetsMinimumYears({ start: '2020-01-02', end: '2021-01-02' }, 1)).toBe(true)
    expect(meetsMinimumYears({ start: '2020-01-02', end: '2021-01-01' }, 1)).toBe(false)
    // 윤년이 끼어도 365일 곱셈처럼 하루가 모자라지 않는다.
    expect(meetsMinimumYears({ start: '2020-02-01', end: '2021-02-01' }, 1)).toBe(true)
    expect(meetsMinimumYears({ start: '2024-12-30', end: '2024-12-30' }, 0)).toBe(true)
  })

  it('입력한 기간을 범위·순서·형식으로 판정한다', () => {
    expect(checkPeriodInput('2021-01-04', '2023-12-28', range)).toBeNull()
    expect(checkPeriodInput(range.start, range.end, range)).toBeNull()
    expect(checkPeriodInput('2019-12-31', '2023-12-28', range)).toMatch(/범위/)
    expect(checkPeriodInput('2021-01-04', '2025-01-02', range)).toMatch(/범위/)
    expect(checkPeriodInput('2023-01-04', '2022-01-04', range)).toMatch(/늦습니다/)
    expect(checkPeriodInput('', '2022-01-04', range)).toMatch(/날짜로/)
  })
})
