/**
 * 전략 정의 계약의 TypeScript 미러.
 *
 * 정본은 `docs/specs/strategy-definition/strategy-definition.schema.json` 과
 * `strategy-catalog-api.schema.json` 이다. 이 파일은 그 구조를 그대로 옮긴 것이며,
 * 여기에만 있는 필드나 여기서 뺀 필드가 있으면 계약이 깨진 것이다.
 *
 * 동적 폼은 `type` 하나로 분기한다. 타입별로 어떤 범위 표현이 오는지는 계약이 보장한다 —
 * INTEGER/DECIMAL 이면 `numericRange` 가 있고 `enumValues` 는 없다. ENUM 이면 그 반대다.
 * BOOLEAN 이면 둘 다 없다. 그래서 전략이 20개로 늘어도 폼 코드는 늘지 않는다.
 */

export type ExecutionScope = 'BACKTEST' | 'LIVE'

export type ParameterType = 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM'

export type ConstraintOperator = 'LT' | 'LTE' | 'GT' | 'GTE' | 'EQ' | 'NE'

export type HistoryRule = 'FIXED' | 'PARAMETER' | 'MAX_PARAMETERS' | 'SUM_PARAMETERS'

export type ChangeType = 'INITIAL' | 'BACKWARD_COMPATIBLE' | 'INCOMPATIBLE'

export type ViolationCode =
  | 'UNKNOWN_STRATEGY'
  | 'UNKNOWN_PARAMETER'
  | 'REQUIRED_PARAMETER_MISSING'
  | 'TYPE_MISMATCH'
  | 'OUT_OF_RANGE'
  | 'STEP_MISMATCH'
  | 'NOT_IN_ENUM'
  | 'CONSTRAINT_VIOLATED'

/** 파라미터 값으로 실릴 수 있는 것. INTEGER·DECIMAL 은 number, BOOLEAN 은 boolean, ENUM 은 string. */
export type ParameterValue = number | boolean | string

/** 확정 값 집합. 전략이 쓰는 모든 파라미터가 빠짐없이 들어간다. */
export type ParameterValueSet = Record<string, ParameterValue>

/** 한쪽 경계만 지정한 열린 범위도 유효하다. 경계가 없으면 그 필드가 없다. */
export interface NumericRange {
  min?: number
  /** 생략 시 true */
  minInclusive?: boolean
  max?: number
  /** 생략 시 true */
  maxInclusive?: boolean
  /** 허용 간격. 판정식은 (value - min) % step === 0 */
  step?: number
}

export interface EnumOption {
  value: string
  displayName: string
}

export interface ParameterDefinition {
  name: string
  displayName: string
  description: string
  type: ParameterType
  required: boolean
  /** required 가 false 면 반드시 있다. required 가 true 면 없다. */
  defaultValue?: ParameterValue
  /** 표시용 단위. 판정에 쓰지 않는다. */
  unit?: string | null
  executionScopes: ExecutionScope[]
  /** INTEGER · DECIMAL 일 때만 있다. */
  numericRange?: NumericRange
  /** ENUM 일 때만 있다. */
  enumValues?: EnumOption[]
}

export interface ParameterConstraint {
  left: string
  operator: ConstraintOperator
  right: string
  /** 위반 시 사용자에게 그대로 보여줄 설명 */
  message: string
}

export interface MinimumHistoryRequirement {
  rule: HistoryRule
  /** FIXED 일 때만 있다. */
  value?: number
  /** PARAMETER 는 1개, MAX_/SUM_ 은 1개 이상. FIXED 일 때는 없다. */
  parameters?: string[]
  /** 생략 시 0 */
  offset?: number
  unit: 'bars'
}

export interface ChangeHistoryEntry {
  definitionVersion: number
  changeType: ChangeType
  changeSummary: string
  /** YYYY-MM-DD */
  changedAt: string
}

export interface StrategyDefinition {
  schemaVersion: string
  strategyId: string
  definitionVersion: number
  displayName: string
  description: string
  /** 사람이 읽는 서술. 기계가 해석할 형태가 아니다. */
  entryCondition: string
  exitCondition: string
  executionScopes: ExecutionScope[]
  /** 조정할 파라미터가 없는 전략은 빈 배열이다. */
  parameters: ParameterDefinition[]
  parameterConstraints: ParameterConstraint[]
  minimumHistoryRequirement: MinimumHistoryRequirement
  /** 최신 우선 정렬 */
  changeHistory: ChangeHistoryEntry[]
  deprecated?: boolean
}

/** GET /strategies 의 목록 항목. */
export interface StrategySummary {
  strategyId: string
  definitionVersion: number
  displayName: string
  description: string
  executionScopes: ExecutionScope[]
  parameterCount: number
  deprecated: boolean
}

export interface ParameterValidationRequest {
  parameters: ParameterValueSet
  /** 생략 시 BACKTEST */
  executionScope?: ExecutionScope
}

export interface ParameterViolation {
  code: ViolationCode
  /** CONSTRAINT_VIOLATED 는 제약의 left, UNKNOWN_STRATEGY 는 null */
  parameterName: string | null
  message: string
  /** 판정 근거. 예: { min: 2, max: 120, actual: 300 } */
  detail?: Record<string, unknown> | null
}

/**
 * 판정 결과. 위반이 하나라도 있으면 `resolvedParameters` 와 `requiredHistoryBars` 는 null 이다.
 * 판정 실패도 HTTP 200 이며 결과는 본문에 담긴다.
 */
export interface ParameterValidationResult {
  strategyId: string
  definitionVersion: number
  valid: boolean
  /** 발견된 모든 위반. 순서는 판정 단계 순, 같은 단계 안에서는 정의의 parameters 선언 순서. */
  violations: ParameterViolation[]
  resolvedParameters: ParameterValueSet | null
  requiredHistoryBars: number | null
}

/** 수치형 파라미터인지 좁힌다. 폼이 min·max·step 을 안전하게 꺼내 쓰기 위한 것이다. */
export function isNumericParameter(
  parameter: ParameterDefinition,
): parameter is ParameterDefinition & { numericRange: NumericRange } {
  return parameter.type === 'INTEGER' || parameter.type === 'DECIMAL'
}

/** 열거형 파라미터인지 좁힌다. */
export function isEnumParameter(
  parameter: ParameterDefinition,
): parameter is ParameterDefinition & { enumValues: EnumOption[] } {
  return parameter.type === 'ENUM'
}

/** 주어진 실행 목적에서 의미를 갖는 파라미터만 남긴다. 나머지는 그 요청에서 없는 것으로 다룬다. */
export function parametersFor(
  definition: StrategyDefinition,
  scope: ExecutionScope = 'BACKTEST',
): ParameterDefinition[] {
  return definition.parameters.filter((parameter) => parameter.executionScopes.includes(scope))
}
