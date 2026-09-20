import { describe, expect, it } from 'vitest'
import type { RuleOperator, ValidationRule } from '@/types/strategyRegistry'
import { RuleEvaluationError, checkRules, evaluateRules } from './validationRules'

/**
 * 화면의 규칙 평가기(T052)가 서버 `RegistryRule.isViolatedBy`와 같은 답을 내는가.
 *
 * <p>규칙은 합성 값이다. 축 이름·사유 코드·문구를 실제 등록표에서 옮겨 적지 않는다 — 평가기가 뜻을
 * 읽지 않는다는 것을 지어낸 이름이 증명한다.
 */

function rule(overrides: Partial<ValidationRule> = {}): ValidationRule {
  return {
    code: 'SYNTHETIC_RULE',
    detail: '합성 규칙을 어겼습니다.',
    left: { axis: 'knob' },
    operator: 'GREATER_THAN',
    right: { value: 10 },
    ...overrides,
  }
}

const context = { UNIVERSE_SIZE: 3 }

describe('evaluateRules', () => {
  it.each<[RuleOperator, number, boolean]>([
    ['GREATER_THAN', 11, true],
    ['GREATER_THAN', 10, false],
    ['GREATER_OR_EQUAL', 10, true],
    ['GREATER_OR_EQUAL', 9, false],
    ['LESS_THAN', 9, true],
    ['LESS_THAN', 10, false],
    ['LESS_OR_EQUAL', 10, true],
    ['LESS_OR_EQUAL', 11, false],
    ['EQUAL', 10, true],
    ['EQUAL', 11, false],
    ['NOT_EQUAL', 11, true],
    ['NOT_EQUAL', 10, false],
  ])('%s: 값 %d과 상수 10은 위반 %s', (operator, value, violated) => {
    const target = rule({ operator })
    expect(evaluateRules([target], { knob: value }, context)).toEqual(violated ? [target] : [])
  })

  // quickstart 5-1: 비중 상한 30 × 섹터 3 = 90 < 100 → 위반, 34 × 3 = 102 → 통과
  const capRule = rule({
    code: 'CAP_CANNOT_FILL',
    left: { axis: 'cap', multiplyByContext: 'UNIVERSE_SIZE' },
    operator: 'LESS_THAN',
    right: { value: 100 },
  })

  it('맥락을 곱한 값으로 판정한다', () => {
    expect(evaluateRules([capRule], { cap: 30 }, context)).toEqual([capRule])
    expect(evaluateRules([capRule], { cap: 34 }, context)).toEqual([])
  })

  // 004 PD-03: 모멘텀은 후보 전체가 아니라 topN 개에만 비중이 간다. 곱셈 인자가 빠지면
  // `maxWeight × topN < 100` 이 `maxWeight < 100` 이 되어 상한을 전부 위반으로 막는다.
  const selectionRule = rule({
    code: 'MAX_WEIGHT_TOO_LOW_FOR_SELECTION',
    left: { axis: 'maxWeight', multiplyByAxis: 'topN' },
    operator: 'LESS_THAN',
    right: { value: 100 },
  })

  it('다른 축을 곱한 값으로 판정한다', () => {
    expect(evaluateRules([selectionRule], { maxWeight: 40, topN: 2 }, context)).toEqual([
      selectionRule,
    ])
    expect(evaluateRules([selectionRule], { maxWeight: 40, topN: 3 }, context)).toEqual([])
  })

  it('곱할 축이 설정에 없으면 판정하지 않는다 — 다른 선정 방식은 무관하다', () => {
    // 003은 고른 selectionMode 에 딸린 축 하나만 저장한다(FR-003). topN 이 없는 설정에서
    // 값을 그대로 쓰면 상한 10%가 위반으로 막힌다.
    expect(evaluateRules([selectionRule], { maxWeight: 10 }, context)).toEqual([])
  })

  const percentSelectionRule = rule({
    code: 'MAX_WEIGHT_TOO_LOW_FOR_SELECTION',
    left: {
      axis: 'maxWeight',
      multiplyByCeil: { context: 'UNIVERSE_SIZE', axis: 'topPercent', divisor: 100 },
    },
    operator: 'LESS_THAN',
    right: { value: 100 },
  })

  it('상위 비율로 고른 섹터 수를 올림한 뒤 상한과 곱한다', () => {
    // 후보 3개 중 50%는 1.5개가 아니라 2개를 고른다. 상한 40%로는 80%만 배분할 수 있다.
    expect(
      evaluateRules([percentSelectionRule], { maxWeight: 40, topPercent: 50 }, context),
    ).toEqual([percentSelectionRule])
    expect(
      evaluateRules([percentSelectionRule], { maxWeight: 50, topPercent: 50 }, context),
    ).toEqual([])
  })

  it('상위 비율 축이 설정에 없으면 판정하지 않는다 — 다른 선정 방식은 무관하다', () => {
    expect(evaluateRules([percentSelectionRule], { maxWeight: 10 }, context)).toEqual([])
  })

  it('곱한 값이 경계와 정확히 같으면 이진 부동소수 오차로 뒤집히지 않는다', () => {
    const decimalRule = rule({
      left: { axis: 'ratio', multiplyByContext: 'UNIVERSE_SIZE' },
      operator: 'LESS_THAN',
      right: { value: 0.3 },
    })
    // 0.1 * 3 === 0.30000000000000004 이므로 반올림하지 않으면 통과로 판정된다.
    expect(evaluateRules([decimalRule], { ratio: 0.1 }, context)).toEqual([])
  })

  it('맥락 피연산자를 읽는다', () => {
    const pickRule = rule({
      left: { axis: 'pick' },
      operator: 'GREATER_THAN',
      right: { context: 'UNIVERSE_SIZE' },
    })
    expect(evaluateRules([pickRule], { pick: 4 }, context)).toEqual([pickRule])
    expect(evaluateRules([pickRule], { pick: 3 }, context)).toEqual([])
  })

  it('피연산자의 축 값이 없으면 판정하지 않는다', () => {
    expect(evaluateRules([capRule], {}, context)).toEqual([])
    expect(evaluateRules([capRule], { other: 1 }, context)).toEqual([])
  })

  it('숫자 문자열도 수로 읽는다', () => {
    expect(evaluateRules([capRule], { cap: '30' }, context)).toEqual([capRule])
  })

  it('어기는 규칙을 모두 등록표 순서대로 돌려준다', () => {
    const second = rule({ code: 'SECOND', left: { axis: 'knob' }, operator: 'LESS_THAN', right: { value: 50 } })
    const first = rule({ code: 'FIRST' })
    expect(evaluateRules([first, second], { knob: 20 }, context)).toEqual([first, second])
  })

  it('대소 비교에 숫자가 아닌 값이 오면 통과로 접지 않고 예외를 던진다', () => {
    expect(() => evaluateRules([rule()], { knob: { mode: 'X' } }, context)).toThrow(
      RuleEvaluationError,
    )
    expect(() => evaluateRules([capRule], { cap: 'wide' }, context)).toThrow(RuleEvaluationError)
  })
})

describe('checkRules', () => {
  it('판정하지 못하면 위반 목록을 비우되 통과로 접지 않고 unjudged를 세운다', () => {
    // 대소 비교에 숫자가 아닌 값이 왔다. 빈 위반 목록만 보고 통과로 읽으면 안 된다(T101).
    const result = checkRules([rule()], { knob: { kind: 'OBJECT' } }, context)
    expect(result).toEqual({ violations: [], unjudged: true })
  })

  it('정상 규칙이면 unjudged가 거짓이고 위반을 그대로 돌려준다', () => {
    const target = rule()
    expect(checkRules([target], { knob: 11 }, context)).toEqual({
      violations: [target],
      unjudged: false,
    })
    expect(checkRules([target], { knob: 9 }, context)).toEqual({ violations: [], unjudged: false })
  })
})
