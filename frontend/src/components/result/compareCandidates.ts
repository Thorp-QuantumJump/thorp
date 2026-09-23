import {
  type AxisValues,
  applicableAxes,
  axisValueEquals,
  checkAxisValue,
  defaultValuesOf,
  isAxisValue,
} from '@/hooks/useStrategySettings'
import type { UsedStrategy } from '@/types/backtestRequest'
import type { AxisValue, StrategyView } from '@/types/strategyRegistry'

/**
 * 비교 후보의 재료(`S15P21E205-419`). 후보는 <b>전략 + 설정값 조합</b>이고 출처는 둘이다 — 등록표 기본값과 내가 써 본 조합
 * (`GET /backtests/used-strategies`). 조건(섹터·기간·비용)은 기준 결과가 들고 있으므로 여기서는 다루지 않는다.
 *
 * <p>화면은 설정값의 뜻을 읽지 않는다. 축 이름은 등록표의 `label`로, 목록형 값은 등록표의 `label`로 바꿔 적고, 등록표에 없는
 * 축이나 후보에 없는 값은 그대로 문자열로 보인다(003 FR-005).
 */

export type CandidateSource = 'default' | 'used'

/** 설정 조합 하나. 같은 전략이라도 설정값이 다르면 다른 후보다. */
export type ParameterCombination = {
  strategy: StrategyView
  parameters: AxisValues
  source: CandidateSource
}

function describeValue(value: AxisValue): string {
  if (typeof value === 'object') return Object.values(value).join(' ')
  return String(value)
}

/**
 * 설정값을 사람이 읽는 한 줄로. 등록표가 이 전략에 적용한다고 한 축만, 등록표의 순서로 적는다.
 * 예: `리밸런싱 주기 월말 · 섹터당 최대 비중 40%`. 축이 없는 전략은 빈 문자열이다.
 */
export function describeParameters(strategy: StrategyView, parameters: AxisValues): string {
  return applicableAxes(strategy, parameters)
    .filter((axis) => parameters[axis.name] !== undefined)
    .map((axis) => {
      const value = parameters[axis.name]
      const choice = axis.choices?.find((candidate) => axisValueEquals(candidate.value, value))
      const shown = choice ? choice.label : describeValue(value) + (axis.unit ?? '')
      return axis.label + ' ' + shown
    })
    .join(' · ')
}

/** 두 설정 조합이 같은가. 적용되는 축의 값만 비교한다 — 조건 밖 축은 저장되지도 않는다(003 FR-003). */
export function parametersEqual(
  strategy: StrategyView,
  left: AxisValues,
  right: AxisValues,
): boolean {
  const axes = applicableAxes(strategy, left)
  if (axes.length !== applicableAxes(strategy, right).length) return false
  return axes.every((axis) => {
    const a = left[axis.name]
    const b = right[axis.name]
    return a !== undefined && b !== undefined && axisValueEquals(a, b)
  })
}

/**
 * 써 본 조합의 설정값을 현재 등록표로 다시 판정한다. 접수가 저장한 값이라 등록표가 바뀐 뒤에는 축이 없어졌거나 범위 밖일 수 있다
 * (계약 §1.12). 적용되는 축이 하나라도 비거나 어긋나면 `null` — 값을 고쳐 넣어 후보로 올리지 않는다(원칙 VI).
 */
export function readUsedParameters(
  strategy: StrategyView,
  parameters: Record<string, unknown>,
): AxisValues | null {
  const values: AxisValues = {}
  for (const [name, value] of Object.entries(parameters)) {
    if (!isAxisValue(value)) return null
    values[name] = value
  }
  const axes = applicableAxes(strategy, values)
  for (const axis of axes) {
    const value = values[axis.name]
    if (value === undefined || checkAxisValue(axis, value) !== null) return null
  }
  // 조건 밖 축이 섞여 있어도 요청에는 적용 축만 싣는다.
  const settled: AxisValues = {}
  for (const axis of axes) settled[axis.name] = values[axis.name]
  return settled
}

/**
 * 전략 하나의 후보 조합들. 기본값이 먼저, 그 다음 써 본 조합을 받은 순서(최근 순)대로. 기본값과 같은 조합·서로 같은 조합·
 * `exclude`(기준 결과 자신)와 같은 조합은 한 번만 남기거나 뺀다.
 */
export function combinationsFor(
  strategy: StrategyView,
  used: readonly UsedStrategy[],
  exclude: { strategyId: string; parameters: Record<string, unknown> } | null,
): ParameterCombination[] {
  const result: ParameterCombination[] = []
  const seen: AxisValues[] = []
  const excluded =
    exclude && exclude.strategyId === strategy.strategyId
      ? readUsedParameters(strategy, exclude.parameters)
      : null
  const push = (parameters: AxisValues, source: CandidateSource) => {
    if (excluded && parametersEqual(strategy, excluded, parameters)) return
    if (seen.some((known) => parametersEqual(strategy, known, parameters))) return
    seen.push(parameters)
    result.push({ strategy, parameters, source })
  }
  push(defaultValuesOf(strategy), 'default')
  for (const item of used) {
    if (item.strategyId !== strategy.strategyId) continue
    const parameters = readUsedParameters(strategy, item.parameters)
    if (parameters) push(parameters, 'used')
  }
  return result
}
