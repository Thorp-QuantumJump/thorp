import type {
  Operand,
  RuleContextKey,
  RuleOperator,
  ValidationRule,
} from '@/types/strategyRegistry'

/**
 * 등록표의 유효성 규칙을 화면이 평가한다(FR-008, research R7-1).
 *
 * <p><b>규칙의 뜻을 읽지 않는다.</b> `left`·`operator`·`right` 네 필드만 기계적으로 평가하고, 축
 * 이름이나 사유 코드로 분기하지 않는다. 분기하면 그것이 전략 지식의 중복이고, 등록표에 규칙이 늘
 * 때마다 화면을 고쳐야 한다.
 *
 * <p><b>서버 `RegistryRule.isViolatedBy`와 같은 규약이다.</b> 조건이 참이면 위반이고, 피연산자가
 * 가리키는 축 값이 없으면 그 규칙은 판정하지 않는다 — 판정할 수 없는 것을 위반으로 접으면 이유 없는
 * 안내가 생긴다. 화면 판정은 사용자를 돕는 것이고, 접수 시점의 판정은 서버가 다시 한다(FR-006).
 */

/** 규칙이 참조하는 맥락 값. 키는 등록표 문법(`RuleContextKey`)이 정한다. */
export type RuleContext = Record<RuleContextKey, number>

/**
 * 대소 비교에 숫자가 아닌 값이 왔다. 등록표가 계약을 어긴 경우다.
 *
 * <p><b>통과로 접지 않는다.</b> 서버는 같은 상황에서 예외를 던지므로, 화면이 조용히 통과시키면
 * 사용자는 아무 안내 없이 접수 거절을 만난다. `WarmupEvaluationError`와 같은 규약이다(원칙 VI).
 */
export class RuleEvaluationError extends Error {
  /** 판정하지 못한 규칙의 사유 코드 */
  readonly code: string

  constructor(message: string, code: string) {
    super(message)
    this.name = 'RuleEvaluationError'
    this.code = code
  }
}

/** `String(n)`이 지수 표기로 바뀌지 않는 범위에서 소수 자릿수를 센다. */
function decimalPlaces(value: number): number {
  const text = String(value)
  if (text.includes('e')) return 10
  const point = text.indexOf('.')
  return point === -1 ? 0 : text.length - point - 1
}

/** 숫자와 숫자 문자열을 수로 읽는다. 서버가 `new BigDecimal(String.valueOf(value))`로 읽는 것과 같다. */
function toNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/**
 * 두 수의 곱. 이진 부동소수 오차를 남기지 않도록 두 수의 소수 자릿수 합으로 반올림한다.
 *
 * `0.1 × 3`이 `0.30000000000000004`가 되면 `< 0.3` 같은 경계 판정이 뒤집힌다(spec Edge Cases).
 */
function multiply(left: number, right: number): number {
  const places = Math.min(10, decimalPlaces(left) + decimalPlaces(right))
  const scale = 10 ** places
  return Math.round(left * right * scale) / scale
}

/** 두 수를 같은 자릿수의 정수로 올려 견준다. `isOnStep`과 같은 까닭이다. */
function compare(left: number, right: number): number {
  const places = Math.min(10, Math.max(decimalPlaces(left), decimalPlaces(right)))
  const scale = 10 ** places
  return Math.sign(Math.round(left * scale) - Math.round(right * scale))
}

/** 피연산자를 값으로 바꾼다. 가리키는 축 값이 없으면 `undefined`다. */
function resolve(
  operand: Operand,
  values: Record<string, unknown>,
  context: RuleContext,
  code: string,
): unknown {
  if ('context' in operand) return context[operand.context]
  if ('value' in operand) return operand.value

  const value = values[operand.axis]
  if (value === undefined || value === null) return undefined
  if (operand.multiplyByContext === undefined) return value

  const numeric = toNumber(value)
  const factor = toNumber(context[operand.multiplyByContext])
  if (numeric === null || factor === null) {
    throw new RuleEvaluationError(
      `규칙 '${code}'의 곱셈에 숫자가 아닌 값이 왔습니다: ${String(value)}`,
      code,
    )
  }
  return multiply(numeric, factor)
}

/** 같은가. 숫자끼리는 값으로, 나머지는 모양 그대로 견준다. */
function equals(left: unknown, right: unknown): boolean {
  if (typeof left === 'number' && typeof right === 'number') return compare(left, right) === 0
  return left === right
}

function test(operator: RuleOperator, left: unknown, right: unknown, code: string): boolean {
  if (operator === 'EQUAL') return equals(left, right)
  if (operator === 'NOT_EQUAL') return !equals(left, right)

  const leftNumber = toNumber(left)
  const rightNumber = toNumber(right)
  if (leftNumber === null || rightNumber === null) {
    throw new RuleEvaluationError(
      `규칙 '${code}'의 대소 비교에 숫자가 아닌 값이 왔습니다: ${String(left)}, ${String(right)}`,
      code,
    )
  }
  const comparison = compare(leftNumber, rightNumber)
  switch (operator) {
    case 'GREATER_THAN':
      return comparison > 0
    case 'GREATER_OR_EQUAL':
      return comparison >= 0
    case 'LESS_THAN':
      return comparison < 0
    case 'LESS_OR_EQUAL':
      return comparison <= 0
  }
}

/**
 * 지금 값이 어기는 규칙을 등록표 순서대로 돌려준다. 어기는 것이 없으면 빈 배열이다.
 *
 * <p>서버의 `validate`는 첫 위반 하나만 돌려주지만 화면은 <b>모두</b> 보인다. 하나만 보이면 그것을
 * 고친 뒤에야 다음 위반을 알게 되어, 사용자가 같은 자리를 여러 번 오가게 된다.
 *
 * @param rules 선택 전략의 `validationRules`
 * @param values 축 이름 → 현재 값. 비워 둔 축은 키가 없다
 * @param context 맥락 값. 지금은 후보 섹터 집합의 크기 하나다
 * @throws RuleEvaluationError 대소 비교나 곱셈에 숫자가 아닌 값이 왔을 때
 */
export function evaluateRules(
  rules: readonly ValidationRule[],
  values: Record<string, unknown>,
  context: RuleContext,
): ValidationRule[] {
  return rules.filter((rule) => {
    const left = resolve(rule.left, values, context, rule.code)
    const right = resolve(rule.right, values, context, rule.code)
    if (left === undefined || right === undefined) return false
    return test(rule.operator, left, right, rule.code)
  })
}
