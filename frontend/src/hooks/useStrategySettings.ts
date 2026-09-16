import { useCallback, useEffect, useMemo, useState } from 'react'
import type {
  AxisValue,
  AxisView,
  RegistryResponse,
  StrategyView,
} from '@/types/strategyRegistry'
import { evaluateWarmup } from '@/utils/warmupRule'

/**
 * 전략 화면의 상태(data-model §8·§9)를 탭 수명 동안 보관한다(FR-017, research R8).
 *
 * <p><b>서버에 저장하지 않는다.</b> `sessionStorage`에만 두어 새로고침과 앞 단계 왕복에는 남고 탭을
 * 닫으면 사라진다. 저장소 접근은 읽기·쓰기 모두 실패해도 되는 것으로 다룬다 — 사생활 보호 모드
 * 등에서 막히면 값이 화면 상태에만 남고, 그것이 사용자를 막지는 않는다.
 *
 * <p><b>등록표가 진실이다.</b> 이 훅은 전략 이름·축·기본값을 하나도 알지 못하고 전부 조회 응답에서
 * 읽는다(FR-005). 보관된 값은 신뢰하지 않는다 — 등록표 버전이나 섹터 수가 바뀌었을 수 있고
 * `sessionStorage`는 사용자가 편집할 수 있는 자리다. 그래서 등록표를 받을 때마다 보관값을 새 범위·
 * 규칙으로 다시 판정한다.
 *
 * <p><b>유효하지 않은 값은 고치지 않고 비운다.</b> SC-002는 허용 범위 밖 값이 화면 상태와
 * `sessionStorage`에 남는 것을 금지하고, FR-017은 그 값을 자동 보정하는 것을 금지한다. 둘을 함께
 * 지키는 길은 그 축의 값을 <b>비워 두고</b> 이유와 함께 기본값 복원 선택을 돌려주는 것이다. 기본값을
 * 대신 넣으면 사용자가 정한 적 없는 값으로 조용히 분석이 나간다. 비어 있는 축이 있는 동안에는
 * 그 전략으로 분석을 시작할 수 없고, 사용자가 복원을 누르거나 새 값을 고르면 풀린다.
 *
 * <p>같은 재검증 로직을 T059의 409(`REGISTRY_VERSION_CHANGED`) 처리가 다시 쓴다 — 새 등록표를
 * 넘기면 이 훅이 같은 절차로 보관값을 다시 판정한다.
 */

/** 014·002와 같은 이름 규칙이다(`thorp.diagnosis.sectorSelection`). */
export const STRATEGY_SETTINGS_STORAGE_KEY = 'thorp.strategySettings'

/** 보관 형식의 번호. 형식을 바꾸면 올린다. 아는 값보다 크면 읽지 않는다. */
export const STRATEGY_SETTINGS_PAYLOAD_VERSION = 1

/** 축 이름 → 현재 값. 비워 둔 축은 키 자체가 없다. */
export type AxisValues = Record<string, AxisValue>

/**
 * 분석 조건(data-model §9). B05는 `minEvaluationYears`·`scenario`만 채운다.
 *
 * `periodStart`·`periodEnd`·`initialAmount`는 B10(T067)이 평가 기간 조회와 등록표 기본값으로 채운다.
 * 그때까지 요청 조립은 등록표의 `initialAmount.default`와 판정이 돌려준 기간을 쓴다.
 */
export type AnalysisCondition = {
  /** 등록표 `analysisConditions.minEvaluationYears` 후보 중 하나. `0`은 제한 없음이다(FR-011a) */
  minEvaluationYears: number
  /** 주 분석은 연속 운용으로 고정한다. 목표 변동성은 이 화면 상태에 만들지 않는다(spec Edge Cases) */
  scenario: 'FULL_PERIOD'
  periodStart?: string
  periodEnd?: string
  initialAmount?: number
}

export type InvalidStoredValueReason =
  /** 보관된 전략이 현재 등록표에 없다. 값 전체를 지웠다 */
  | 'STRATEGY_REMOVED'
  /** 보관된 항목이 현재 등록표의 그 전략에 없다. 값을 지웠다 */
  | 'AXIS_REMOVED'
  /** 목록형 항목인데 후보에 없는 값이다 */
  | 'NOT_IN_CHOICES'
  /** 후보이긴 하나 지금 조건에서 고를 수 없다(`choices[].selectable: false`) */
  | 'CHOICE_UNSELECTABLE'
  /** 입력형 항목인데 최소·최대 밖이거나 숫자가 아니다 */
  | 'OUT_OF_RANGE'
  /** 입력형 항목인데 등록된 간격에 맞지 않는다 */
  | 'OFF_STEP'
  /** 최소 평가 기간의 보관값이 등록표 후보에 없어 기본값으로 시작했다 */
  | 'CONDITION_NOT_IN_CHOICES'

/**
 * 보관되어 있었으나 현재 등록표에서 쓸 수 없는 값 하나.
 *
 * `restorable`이면 그 축은 비어 있고 `resetAxis`로 기본값을 넣을 수 있다. 아니면 이미 지운
 * 값이라 알리기만 한다.
 */
export type InvalidStoredValue = {
  /** `null`이면 전략과 무관한 분석 조건이다 */
  strategyId: string | null
  /** `null`이면 전략 전체가 사라진 경우다 */
  axisName: string | null
  storedValue: unknown
  reason: InvalidStoredValueReason
  /** 사용자에게 보일 한 문장. 등록표의 라벨·단위·범위를 그대로 쓴다 */
  message: string
  restorable: boolean
}

/** `sessionStorage`에 두는 모양. 등록표 버전·섹터 수를 함께 담아 재검증의 근거로 삼는다. */
export type StoredStrategySettings = {
  registryVersion: string
  universeSize: number | null
  selectedStrategyId: string | null
  valuesByStrategy: Record<string, AxisValues>
  analysisCondition: AnalysisCondition
  invalidValues: InvalidStoredValue[]
}

// ---------------------------------------------------------------------------
// 값 비교·판정 — 축 이름이나 전략 ID로 분기하지 않는다(research R7-1)
// ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isScalar(value: unknown): value is string | number {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value))
}

/** 홑값이거나 홑값만 담은 한 겹 객체인가(`types/strategyRegistry.ts`의 `AxisValue`). */
export function isAxisValue(value: unknown): value is AxisValue {
  if (isScalar(value)) return true
  if (!isRecord(value)) return false
  return Object.values(value).every(isScalar)
}

/**
 * 축 값 둘이 같은가. 합성 값(리밸런싱 주기)은 칸마다 견준다.
 *
 * 칸의 뜻을 읽지 않는다 — 같은 키에 같은 값이면 같은 것이다. 서버의 `ParameterAxis.indexOf`가
 * `equals`로만 비교하는 것과 같은 규약이다(data-model §5).
 */
export function axisValueEquals(left: AxisValue, right: AxisValue): boolean {
  if (typeof left !== 'object' || typeof right !== 'object') return left === right
  const leftKeys = Object.keys(left)
  const rightKeys = Object.keys(right)
  if (leftKeys.length !== rightKeys.length) return false
  return leftKeys.every((key) => key in right && left[key] === right[key])
}

/** `String(n)`이 지수 표기로 바뀌지 않는 범위에서 소수 자릿수를 센다. */
function decimalPlaces(value: number): number {
  const text = String(value)
  if (text.includes('e')) return 10
  const point = text.indexOf('.')
  return point === -1 ? 0 : text.length - point - 1
}

/**
 * 값이 `min`에서 `step` 간격 위에 놓이는가.
 *
 * <p><b>부동소수점 나머지 연산을 쓰지 않는다.</b> `(0.3 - 0.1) % 0.1`은 0이 아니다. 등록표의
 * `step`을 소수 문자열로 보고 셋을 같은 자릿수의 정수로 올려 견준다(spec Edge Cases).
 */
export function isOnStep(value: number, min: number, step: number): boolean {
  const places = Math.min(10, Math.max(decimalPlaces(value), decimalPlaces(min), decimalPlaces(step)))
  const scale = 10 ** places
  const scaled = (n: number) => Math.round(n * scale)
  const stepScaled = scaled(step)
  if (stepScaled <= 0) return true
  return (scaled(value) - scaled(min)) % stepScaled === 0
}

/**
 * 값 하나가 축의 허용 범위 안인가. 위반이면 그 이유를, 아니면 `null`을 돌려준다.
 *
 * 목록형은 후보 목록에 있는지와 그 후보가 지금 조건에서 고를 수 있는지를 본다 — 후보의
 * `selectable`은 서버가 섹터 수로 미리 판정해 내려 준 값이다(계약 §1). 입력형은 범위와 간격을 본다.
 */
export function checkAxisValue(
  axis: AxisView,
  value: unknown,
): 'NOT_IN_CHOICES' | 'CHOICE_UNSELECTABLE' | 'OUT_OF_RANGE' | 'OFF_STEP' | null {
  if (axis.inputType === 'CHOICE') {
    if (!isAxisValue(value)) return 'NOT_IN_CHOICES'
    const choice = (axis.choices ?? []).find((candidate) => axisValueEquals(candidate.value, value))
    if (!choice) return 'NOT_IN_CHOICES'
    return choice.selectable ? null : 'CHOICE_UNSELECTABLE'
  }

  const range = axis.range
  if (!range) return 'OUT_OF_RANGE'
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'OUT_OF_RANGE'
  if (value < range.min || value > range.max) return 'OUT_OF_RANGE'
  return isOnStep(value, range.min, range.step) ? null : 'OFF_STEP'
}

function displayValue(value: unknown): string {
  if (isScalar(value)) return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

/** `checkAxisValue`가 돌려줄 수 있는 이유만 모은 것. 전략·항목이 사라진 경우는 축이 없어 여기 없다. */
type AxisProblemReason = NonNullable<ReturnType<typeof checkAxisValue>>

/** 판정 이유를 사용자에게 보일 한 문장으로. 라벨·단위·범위는 등록표에서 그대로 읽는다. */
function describeAxisProblem(axis: AxisView, value: unknown, reason: AxisProblemReason): string {
  const shown = displayValue(value)
  const unit = axis.unit ?? ''
  switch (reason) {
    case 'NOT_IN_CHOICES':
      return `'${axis.label}'의 저장값 ${shown}은(는) 현재 등록표의 후보에 없습니다.`
    case 'CHOICE_UNSELECTABLE':
      return `'${axis.label}'의 저장값 ${shown}은(는) 지금 조건에서는 고를 수 없는 후보입니다.`
    case 'OUT_OF_RANGE':
      return axis.range
        ? `'${axis.label}'의 저장값 ${shown}은(는) 허용 범위(${axis.range.min}~${axis.range.max}${unit}) 밖입니다.`
        : `'${axis.label}'의 저장값 ${shown}은(는) 허용 범위 밖입니다.`
    case 'OFF_STEP':
      return `'${axis.label}'의 저장값 ${shown}은(는) 등록된 간격(${axis.range?.step ?? ''}${unit})에 맞지 않습니다.`
  }
}

// ---------------------------------------------------------------------------
// 보관·복원
// ---------------------------------------------------------------------------

function isInvalidStoredValue(value: unknown): value is InvalidStoredValue {
  if (!isRecord(value)) return false
  return (
    (value.strategyId === null || typeof value.strategyId === 'string') &&
    (value.axisName === null || typeof value.axisName === 'string') &&
    typeof value.reason === 'string' &&
    typeof value.message === 'string' &&
    typeof value.restorable === 'boolean'
  )
}

/**
 * 보관된 값을 읽는다. 모양이 어긋나면 통째로 버리고 `null`이다 — 일부만 살려 쓰면 어느 값이
 * 사용자의 것인지 알 수 없다. 저장소가 막혀 있어도 예외를 올리지 않는다.
 */
function readStoredSettings(): StoredStrategySettings | null {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY)
  } catch {
    return null
  }
  if (raw === null) return null

  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(payload)) return null
  if (payload.version !== STRATEGY_SETTINGS_PAYLOAD_VERSION) return null
  if (typeof payload.registryVersion !== 'string') return null
  if (payload.universeSize !== null && typeof payload.universeSize !== 'number') return null
  if (payload.selectedStrategyId !== null && typeof payload.selectedStrategyId !== 'string') {
    return null
  }
  if (!isRecord(payload.valuesByStrategy)) return null
  if (!isRecord(payload.analysisCondition)) return null

  const valuesByStrategy: Record<string, AxisValues> = {}
  for (const [strategyId, values] of Object.entries(payload.valuesByStrategy)) {
    if (!isRecord(values)) return null
    const entries: AxisValues = {}
    for (const [axisName, value] of Object.entries(values)) {
      // 모양이 어긋난 값은 재검증에서 걸리도록 그대로 넘기지 않고 여기서 페이로드를 버린다.
      // 어떤 값이었는지 말할 수 없는 것을 "유효하지 않다"고 알릴 수는 없기 때문이다.
      if (!isAxisValue(value)) return null
      entries[axisName] = value
    }
    valuesByStrategy[strategyId] = entries
  }

  const condition = payload.analysisCondition
  if (typeof condition.minEvaluationYears !== 'number') return null
  const analysisCondition: AnalysisCondition = {
    minEvaluationYears: condition.minEvaluationYears,
    scenario: 'FULL_PERIOD',
  }
  if (typeof condition.periodStart === 'string') analysisCondition.periodStart = condition.periodStart
  if (typeof condition.periodEnd === 'string') analysisCondition.periodEnd = condition.periodEnd
  if (typeof condition.initialAmount === 'number') {
    analysisCondition.initialAmount = condition.initialAmount
  }

  const invalidValues = Array.isArray(payload.invalidValues)
    ? payload.invalidValues.filter(isInvalidStoredValue)
    : []

  return {
    registryVersion: payload.registryVersion,
    universeSize: payload.universeSize,
    selectedStrategyId: payload.selectedStrategyId,
    valuesByStrategy,
    analysisCondition,
    invalidValues,
  }
}

function writeStoredSettings(state: StoredStrategySettings): void {
  try {
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({ version: STRATEGY_SETTINGS_PAYLOAD_VERSION, ...state }),
    )
  } catch {
    // 저장소가 막힌 환경에서는 화면 상태로만 동작한다. 화면을 오류로 막지 않는다(research R8).
  }
}

// ---------------------------------------------------------------------------
// 재검증
// ---------------------------------------------------------------------------

/** 전략의 모든 축을 등록표 기본값으로 채운 값. 추천이 아니라 편의용 출발점이다(FR-004). */
export function defaultValuesOf(strategy: StrategyView): AxisValues {
  return Object.fromEntries(strategy.axes.map((axis) => [axis.name, axis.defaultValue]))
}

function toNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return Number(value)
  }
  return null
}

/** 등록표가 정한 최소 평가 기간 후보(숫자만). 화면이 `0`·`1`·`3`을 코드에 적지 않는다. */
function minEvaluationYearChoices(registry: RegistryResponse): number[] {
  return registry.analysisConditions.minEvaluationYears.choices
    .map((choice) => toNumber(choice.value))
    .filter((value): value is number => value !== null)
}

function minEvaluationYearsDefault(registry: RegistryResponse): number {
  const condition = registry.analysisConditions.minEvaluationYears
  return toNumber(condition.default) ?? minEvaluationYearChoices(registry)[0] ?? Number.NaN
}

function minEvaluationYearsLabel(registry: RegistryResponse, value: number): string {
  const choice = registry.analysisConditions.minEvaluationYears.choices.find(
    (candidate) => toNumber(candidate.value) === value,
  )
  return choice?.label ?? String(value)
}

function freshSettings(
  registry: RegistryResponse,
  universeSize: number | null,
): StoredStrategySettings {
  return {
    registryVersion: registry.registryVersion,
    universeSize,
    selectedStrategyId: null,
    valuesByStrategy: {},
    analysisCondition: {
      minEvaluationYears: minEvaluationYearsDefault(registry),
      scenario: 'FULL_PERIOD',
    },
    invalidValues: [],
  }
}

/**
 * 보관값을 지금 받은 등록표와 섹터 수로 다시 판정한다(FR-017, spec Edge Cases).
 *
 * <ul>
 *   <li>유효한 값은 그대로 둔다.</li>
 *   <li>유효하지 않은 값은 <b>비우고</b> 이유와 기본값 복원 선택을 `invalidValues`에 남긴다.
 *       자동으로 고치지 않는다.</li>
 *   <li>등록표에서 사라진 전략·항목의 값은 둘 곳이 없어 지우고 그 사실을 남긴다.</li>
 *   <li>한 번 고른 전략에 새로 생긴 항목은 기본값으로 채운다 — 사용자 값이 없던 자리라 고치는 것이
 *       아니다.</li>
 *   <li>앞서 비워 둔 항목은 사용자가 복원하기 전까지 비운 채로 옮긴다.</li>
 * </ul>
 *
 * 순수 함수다. 훅 밖에서 T059의 409 처리도 같은 절차를 쓴다.
 */
export function reconcileStoredSettings(
  stored: StoredStrategySettings | null,
  registry: RegistryResponse,
  universeSize: number | null,
): StoredStrategySettings {
  if (stored === null) return freshSettings(registry, universeSize)

  const strategies = new Map(registry.strategies.map((strategy) => [strategy.strategyId, strategy]))
  const invalidValues: InvalidStoredValue[] = []
  const valuesByStrategy: Record<string, AxisValues> = {}

  // 앞선 판정에서 비워 둔 항목. 아직 복원되지 않았으면 그대로 옮긴다.
  const pending = stored.invalidValues.filter(
    (entry) => entry.restorable && entry.strategyId !== null && entry.axisName !== null,
  )
  const isPending = (strategyId: string, axisName: string) =>
    pending.some((entry) => entry.strategyId === strategyId && entry.axisName === axisName)

  for (const [strategyId, values] of Object.entries(stored.valuesByStrategy)) {
    const strategy = strategies.get(strategyId)
    if (!strategy) {
      invalidValues.push({
        strategyId,
        axisName: null,
        storedValue: values,
        reason: 'STRATEGY_REMOVED',
        message: `저장된 전략 '${strategyId}'의 설정은 현재 등록표에 그 전략이 없어 지웠습니다.`,
        restorable: false,
      })
      continue
    }

    const axes = new Map(strategy.axes.map((axis) => [axis.name, axis]))
    const next: AxisValues = {}

    for (const [axisName, value] of Object.entries(values)) {
      const axis = axes.get(axisName)
      if (!axis) {
        invalidValues.push({
          strategyId,
          axisName,
          storedValue: value,
          reason: 'AXIS_REMOVED',
          message: `'${strategy.name}'의 저장된 항목 '${axisName}'은(는) 현재 등록표에 없어 지웠습니다.`,
          restorable: false,
        })
        continue
      }

      const reason = checkAxisValue(axis, value)
      if (reason === null) {
        next[axisName] = value
        continue
      }
      // 비워 둔다. 기본값을 대신 넣지 않는다.
      invalidValues.push({
        strategyId,
        axisName,
        storedValue: value,
        reason,
        message: describeAxisProblem(axis, value, reason),
        restorable: true,
      })
    }

    for (const axis of strategy.axes) {
      if (axis.name in next) continue
      if (isPending(strategyId, axis.name)) {
        const entry = pending.find(
          (candidate) => candidate.strategyId === strategyId && candidate.axisName === axis.name,
        )
        if (entry) invalidValues.push(entry)
        continue
      }
      // 사용자 값이 없던 자리다. 등록표에 항목이 새로 생긴 경우가 여기에 해당한다.
      if (!(axis.name in values)) next[axis.name] = axis.defaultValue
    }

    valuesByStrategy[strategyId] = next
  }

  const conditionChoices = minEvaluationYearChoices(registry)
  let minEvaluationYears = stored.analysisCondition.minEvaluationYears
  if (!conditionChoices.includes(minEvaluationYears)) {
    const fallback = minEvaluationYearsDefault(registry)
    invalidValues.push({
      strategyId: null,
      axisName: 'minEvaluationYears',
      storedValue: minEvaluationYears,
      reason: 'CONDITION_NOT_IN_CHOICES',
      message: `최소 평가 기간의 저장값 ${displayValue(minEvaluationYears)}은(는) 현재 등록표 후보에 없어 '${minEvaluationYearsLabel(registry, fallback)}'으로 시작합니다.`,
      restorable: false,
    })
    minEvaluationYears = fallback
  }

  const selectedStrategyId =
    stored.selectedStrategyId !== null && strategies.has(stored.selectedStrategyId)
      ? stored.selectedStrategyId
      : null

  return {
    registryVersion: registry.registryVersion,
    universeSize,
    selectedStrategyId,
    valuesByStrategy,
    analysisCondition: { ...stored.analysisCondition, minEvaluationYears, scenario: 'FULL_PERIOD' },
    invalidValues,
  }
}

// ---------------------------------------------------------------------------
// 훅
// ---------------------------------------------------------------------------

export type StrategySettings = {
  /** 등록표를 받아 보관값 재검증까지 마쳤는가. 그 전에는 고르기·바꾸기가 무시된다 */
  ready: boolean
  registryVersion: string | null
  selectedStrategyId: string | null
  selectedStrategy: StrategyView | null
  /** 선택 전략의 축 값. 비워 둔 축은 키가 없다 */
  values: AxisValues | null
  valuesByStrategy: Record<string, AxisValues>
  analysisCondition: AnalysisCondition
  invalidValues: InvalidStoredValue[]
  /** 선택 전략의 현재 설정으로 계산한 워밍업. 축이 비어 있거나 계산할 수 없으면 `null` */
  warmupTradingDays: number | null
  /** 모든 축 값이 등록표 기본값과 같은가. 저장하지 않고 매번 계산한다(FR-002) */
  isDefault: (strategyId: string) => boolean
  /** 모든 축에 값이 있는가. 비워 둔 축이 있으면 분석을 시작할 수 없다 */
  isComplete: (strategyId: string) => boolean
  select: (strategyId: string) => void
  /** 허용 범위 안이면 반영하고 `null`, 밖이면 반영하지 않고 이유를 돌려준다(FR-006) */
  setValue: (strategyId: string, axisName: string, value: AxisValue) => InvalidStoredValueReason | null
  resetAxis: (strategyId: string, axisName: string) => void
  resetAll: (strategyId: string) => void
  setAnalysisCondition: (patch: Partial<Omit<AnalysisCondition, 'scenario'>>) => void
}

/**
 * @param registry 조회한 등록표. 아직 없으면 `null`
 * @param universeSize 002가 확정한 섹터 수. 아직 모르면 `null`. 바뀌면 보관값을 다시 판정한다
 */
export function useStrategySettings(
  registry: RegistryResponse | null,
  universeSize: number | null,
): StrategySettings {
  const [state, setState] = useState<StoredStrategySettings | null>(readStoredSettings)

  const ready =
    state !== null &&
    registry !== null &&
    state.registryVersion === registry.registryVersion &&
    state.universeSize === universeSize

  // 등록표가 오거나 버전·섹터 수가 바뀌면 보관값을 다시 판정한다.
  useEffect(() => {
    if (registry === null) return
    setState((current) => reconcileStoredSettings(current, registry, universeSize))
  }, [registry, universeSize])

  // 판정을 마친 상태만 보관한다. 판정 전의 값은 이미 보관되어 있던 것이다.
  useEffect(() => {
    if (ready && state !== null) writeStoredSettings(state)
  }, [ready, state])

  const strategies = useMemo(
    () => new Map((registry?.strategies ?? []).map((strategy) => [strategy.strategyId, strategy])),
    [registry],
  )

  const selectedStrategy =
    ready && state.selectedStrategyId !== null
      ? (strategies.get(state.selectedStrategyId) ?? null)
      : null
  const values =
    ready && state.selectedStrategyId !== null
      ? (state.valuesByStrategy[state.selectedStrategyId] ?? null)
      : null

  const isComplete = useCallback(
    (strategyId: string) => {
      if (!ready) return false
      const strategy = strategies.get(strategyId)
      if (!strategy) return false
      const current = state.valuesByStrategy[strategyId] ?? defaultValuesOf(strategy)
      return strategy.axes.every((axis) => axis.name in current)
    },
    [ready, state, strategies],
  )

  const isDefault = useCallback(
    (strategyId: string) => {
      if (!ready) return false
      const strategy = strategies.get(strategyId)
      if (!strategy) return false
      const current = state.valuesByStrategy[strategyId] ?? defaultValuesOf(strategy)
      return strategy.axes.every(
        (axis) => axis.name in current && axisValueEquals(current[axis.name], axis.defaultValue),
      )
    },
    [ready, state, strategies],
  )

  const warmupTradingDays = useMemo(() => {
    if (!selectedStrategy || !values) return null
    if (!selectedStrategy.axes.every((axis) => axis.name in values)) return null
    try {
      return evaluateWarmup(selectedStrategy.warmupRule, values)
    } catch {
      return null
    }
  }, [selectedStrategy, values])

  const select = useCallback(
    (strategyId: string) => {
      const strategy = strategies.get(strategyId)
      if (!strategy) return
      setState((current) => {
        if (current === null) return current
        // 처음 고르는 전략은 등록표 기본값으로 채운다. 이미 값이 있으면 그대로 둔다 — 카드를
        // 옮겼다 돌아와도 바꾼 값이 남아야 한다(spec Edge Cases).
        const valuesByStrategy =
          strategyId in current.valuesByStrategy
            ? current.valuesByStrategy
            : { ...current.valuesByStrategy, [strategyId]: defaultValuesOf(strategy) }
        return { ...current, selectedStrategyId: strategyId, valuesByStrategy }
      })
    },
    [strategies],
  )

  const withoutInvalid = (
    entries: InvalidStoredValue[],
    strategyId: string,
    axisName: string | null,
  ) =>
    entries.filter(
      (entry) =>
        !(entry.strategyId === strategyId && (axisName === null || entry.axisName === axisName)),
    )

  const setValue = useCallback(
    (strategyId: string, axisName: string, value: AxisValue): InvalidStoredValueReason | null => {
      const axis = strategies.get(strategyId)?.axes.find((candidate) => candidate.name === axisName)
      if (!axis) return 'AXIS_REMOVED'
      const reason = checkAxisValue(axis, value)
      if (reason !== null) return reason
      setState((current) => {
        if (current === null) return current
        const strategyValues = { ...(current.valuesByStrategy[strategyId] ?? {}), [axisName]: value }
        return {
          ...current,
          valuesByStrategy: { ...current.valuesByStrategy, [strategyId]: strategyValues },
          invalidValues: withoutInvalid(current.invalidValues, strategyId, axisName),
        }
      })
      return null
    },
    [strategies],
  )

  const resetAxis = useCallback(
    (strategyId: string, axisName: string) => {
      const axis = strategies.get(strategyId)?.axes.find((candidate) => candidate.name === axisName)
      if (!axis) return
      setState((current) => {
        if (current === null) return current
        const strategyValues = {
          ...(current.valuesByStrategy[strategyId] ?? {}),
          [axisName]: axis.defaultValue,
        }
        return {
          ...current,
          valuesByStrategy: { ...current.valuesByStrategy, [strategyId]: strategyValues },
          invalidValues: withoutInvalid(current.invalidValues, strategyId, axisName),
        }
      })
    },
    [strategies],
  )

  const resetAll = useCallback(
    (strategyId: string) => {
      const strategy = strategies.get(strategyId)
      if (!strategy) return
      setState((current) => {
        if (current === null) return current
        return {
          ...current,
          valuesByStrategy: {
            ...current.valuesByStrategy,
            [strategyId]: defaultValuesOf(strategy),
          },
          invalidValues: withoutInvalid(current.invalidValues, strategyId, null),
        }
      })
    },
    [strategies],
  )

  const setAnalysisCondition = useCallback(
    (patch: Partial<Omit<AnalysisCondition, 'scenario'>>) => {
      if (registry === null) return
      setState((current) => {
        if (current === null) return current
        const next: AnalysisCondition = { ...current.analysisCondition, ...patch, scenario: 'FULL_PERIOD' }
        // 등록표 후보에 없는 최소 평가 기간은 반영하지 않는다(FR-006과 같은 규약).
        if (
          patch.minEvaluationYears !== undefined &&
          !minEvaluationYearChoices(registry).includes(patch.minEvaluationYears)
        ) {
          next.minEvaluationYears = current.analysisCondition.minEvaluationYears
        }
        return { ...current, analysisCondition: next }
      })
    },
    [registry],
  )

  return {
    ready,
    registryVersion: ready ? state.registryVersion : null,
    selectedStrategyId: ready ? state.selectedStrategyId : null,
    selectedStrategy,
    values,
    valuesByStrategy: ready ? state.valuesByStrategy : {},
    analysisCondition: ready
      ? state.analysisCondition
      : { minEvaluationYears: Number.NaN, scenario: 'FULL_PERIOD' },
    invalidValues: ready ? state.invalidValues : [],
    warmupTradingDays,
    isDefault,
    isComplete,
    select,
    setValue,
    resetAxis,
    resetAll,
    setAnalysisCondition,
  }
}
