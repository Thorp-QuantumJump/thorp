import { describe, expect, it } from 'vitest'
import { evaluateWarmup, WarmupEvaluationError } from './warmupRule'

/**
 * 워밍업 산출 규칙을 화면이 서버와 같게 계산하는지 고정한다(data-model §6, quickstart 7-1).
 *
 * <p><b>축 둘을 더하는 경우가 이 검사의 핵심이다.</b> 첫 설계(`{basis, plus}`)는 '최근 구간 제외'가
 * 사용자가 고르는 축이라는 사실을 담지 못해 바꿨다(research R4). 축 하나짜리만 검사하면 그 결함이
 * 되살아나도 드러나지 않는다.
 */
describe('evaluateWarmup', () => {
  it('축 둘을 더한다', () => {
    // quickstart 7-1: 관측 기간 60 + 최근 구간 제외 21 = 81
    expect(
      evaluateWarmup({ sum: ['lookbackDays', 'skipRecentDays'] }, { lookbackDays: 60, skipRecentDays: 21 }),
    ).toBe(81)
  })

  it('둘째 항목을 바꾸면 결과가 따라 바뀐다', () => {
    const rule = { sum: ['lookbackDays', 'skipRecentDays'] }
    expect(evaluateWarmup(rule, { lookbackDays: 60, skipRecentDays: 0 })).toBe(60)
    expect(evaluateWarmup(rule, { lookbackDays: 60, skipRecentDays: 21 })).toBe(81)
  })

  it('축 하나짜리 규칙도 그 축의 현재 값을 읽는다', () => {
    expect(evaluateWarmup({ sum: ['lookbackDays'] }, { lookbackDays: 252 })).toBe(252)
  })

  it('상수 항목을 그대로 더한다', () => {
    expect(evaluateWarmup({ sum: ['lookbackDays', 5] }, { lookbackDays: 60 })).toBe(65)
  })

  it('규칙이 없는 전략은 워밍업이 0이다', () => {
    expect(evaluateWarmup(undefined, {})).toBe(0)
  })

  it('규칙에 없는 축 값은 결과에 섞이지 않는다', () => {
    expect(evaluateWarmup({ sum: ['lookbackDays'] }, { lookbackDays: 60, maxWeight: 40 })).toBe(60)
  })

  it('축 값이 없으면 예외다. 0으로 채우지 않는다', () => {
    // 없는 값을 0으로 채우면 워밍업이 조용히 짧아지고 평가 구간이 실제보다 길게 잡힌다.
    expect(() => evaluateWarmup({ sum: ['lookbackDays'] }, {})).toThrow(WarmupEvaluationError)
    expect(() => evaluateWarmup({ sum: ['lookbackDays', 'skipRecentDays'] }, { lookbackDays: 60 })).toThrow(
      /skipRecentDays/,
    )
  })

  it('정수가 아닌 값은 예외다', () => {
    expect(() => evaluateWarmup({ sum: ['lookbackDays'] }, { lookbackDays: 60.5 })).toThrow(
      WarmupEvaluationError,
    )
    expect(() => evaluateWarmup({ sum: ['rebalance'] }, { rebalance: 'MONTH_END' })).toThrow(
      WarmupEvaluationError,
    )
  })

  it('예외가 어느 축 때문인지 말한다', () => {
    try {
      evaluateWarmup({ sum: ['lookbackDays', 'skipRecentDays'] }, { lookbackDays: 60 })
      expect.unreachable('축 값이 없으면 예외여야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(WarmupEvaluationError)
      expect((error as WarmupEvaluationError).axis).toBe('skipRecentDays')
    }
  })
})
