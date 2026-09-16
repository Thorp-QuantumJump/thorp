import type { WarmupRule } from '@/types/strategyRegistry'

/**
 * 워밍업 계산에 필요한 값이 없거나 정수가 아닐 때 던진다.
 *
 * <p><b>값을 지어내지 않는다.</b> 없는 축 값을 0으로 채우면 워밍업이 조용히 짧아지고, 그 결과 평가
 * 구간이 실제보다 길게 잡힌다. 서버의 `WarmupRule.evaluate`가 같은 상황에서 예외를 던지는 것과
 * 같은 규약이다(원칙 IV·VI).
 */
export class WarmupEvaluationError extends Error {
  /** 문제가 된 축 이름. 상수 항목의 문제면 `null`이다. */
  readonly axis: string | null

  constructor(message: string, axis: string | null) {
    super(message)
    this.name = 'WarmupEvaluationError'
    this.axis = axis
  }
}

/**
 * 항목 하나를 정수로 읽는다. 숫자와 숫자 문자열을 받고, 그 밖의 것과 소수는 거절한다.
 *
 * 서버가 `new BigDecimal(String.valueOf(value))` 뒤 `intValueExact()`로 정수를 요구하는 것과 같다.
 */
function integerOf(value: unknown, axis: string | null): number {
  const numeric =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && value.trim() !== ''
        ? Number(value)
        : Number.NaN

  if (!Number.isInteger(numeric)) {
    const where = axis === null ? '상수 항목' : `축 '${axis}'의 값`
    throw new WarmupEvaluationError(
      `워밍업 계산의 ${where}이(가) 정수가 아닙니다: ${String(value)}`,
      axis,
    )
  }
  return numeric
}

/**
 * 이 설정값에서의 워밍업 거래일 수(data-model §6, research R4).
 *
 * <p>`sum` 배열의 항목을 모두 더한다. 문자열 항목은 축 이름이라 그 축의 <b>현재 값</b>으로 바꿔
 * 읽고, 숫자 항목은 상수 그대로 더한다. 규칙이 없는 전략(`warmupRule` 생략)은 워밍업이 0이다.
 *
 * <p><b>축 값이 없거나 정수가 아니면 예외다.</b> 서버 `WarmupRule.evaluate`와 같은 규약이며,
 * 화면이 설정을 바꿀 때마다 이 함수로 다시 계산해 카드를 판정한다(FR-011).
 *
 * @param rule 등록표가 준 산출 규칙. 없으면 0
 * @param values 축 이름 → 현재 값
 */
export function evaluateWarmup(
  rule: WarmupRule | undefined,
  values: Record<string, unknown>,
): number {
  if (rule === undefined) return 0

  let total = 0
  for (const term of rule.sum) {
    if (typeof term === 'string') {
      const value = values[term]
      if (value === undefined || value === null) {
        throw new WarmupEvaluationError(`워밍업 계산에 필요한 축 값이 없습니다: ${term}`, term)
      }
      total += integerOf(value, term)
    } else {
      total += integerOf(term, null)
    }
  }
  return total
}
