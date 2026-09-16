/**
 * spec 003 전략·파라미터 등록표 조회 API의 응답 타입.
 *
 * 원본 계약은 `specs/003-strategy-settings/contracts/strategy-registry-api.md` §1이며 이 파일의
 * 필드는 그것과 1:1이다.
 *
 * **전략 이름·축·기본값을 이 파일에 상수로 두지 않는다(FR-005).** 등록표는 서버가 보관하는 단일
 * 원본이고, 화면은 조회한 응답만으로 카드와 입력 항목을 구성한다. 타입은 모양만 정하고 값은
 * 서버가 정한다 — `types/paramSearch.ts`가 007에서 같은 이유로 같은 규칙을 지킨다.
 *
 * 세 가지를 계약에서 그대로 옮겼다.
 *
 * - **값이 없는 필드는 `null`이 아니라 생략된다.** `unit`이 없는 축, 워밍업이 없는 전략의
 *   `warmupRule`이 그렇다. 그래서 선택 속성(`?`)으로 두고 `T | null`로 두지 않는다.
 * - **`selectable: false`인 것도 목록에 남는다.** 활성이되 지금 조건에서 고를 수 없다는 뜻이며,
 *   사용자가 왜 고를 수 없는지 알아야 하기 때문이다. 비활성 전략은 아예 실리지 않는다.
 * - **유효성 규칙은 뜻이 아니라 조건으로 내려온다.** 화면은 `left`·`operator`·`right`를 기계적으로
 *   평가할 뿐 축 이름이나 코드로 분기하지 않는다 — 분기하면 그것이 전략 지식의 중복이다.
 */

/**
 * 홑값. 유효성 규칙의 상수와 분석 조건의 후보가 갖는 형이다.
 *
 * 규칙의 연산자가 대소 비교뿐이라 피연산자는 견줄 수 있는 값이어야 하고, 분석 조건은 등록표가
 * 숫자만 담는다(data-model §7). 그래서 이 둘은 축 값과 달리 넓히지 않는다.
 */
export type ScalarValue = string | number

/**
 * 여러 칸이 모여 하나의 뜻을 이루는 축 값이다.
 *
 * 리밸런싱 주기가 그렇다 — `{ mode: 'CALENDAR', unit: 'MONTH_END' }`처럼 방식과 그 방식의 값이
 * 함께 와야 뜻이 선다(data-model §5).
 *
 * <b>칸 이름을 좁히지 않는다.</b> `mode`·`unit`·`days`를 타입에 적으면 화면이 리밸런싱 주기라는
 * 특정 축을 아는 셈이 되고, 그것이 FR-005가 막으려는 전략 지식의 중복이다. 화면은 이 값의 뜻을
 * 읽지 않고 등록표가 준 `label`로 보이며, 값 자체는 요청에 그대로 되돌려 보낸다.
 */
export type CompositeAxisValue = { readonly [field: string]: ScalarValue }

/**
 * 축의 후보와 기본값이 가질 수 있는 형.
 *
 * 홑값이 대부분이지만 합성 값도 온다. 백엔드가 이 자리를 `Object`로 둔 것도 같은 까닭이다
 * (`StrategyRegistryResponse.Choice.value`·`Axis.defaultValue`).
 */
export type AxisValue = ScalarValue | CompositeAxisValue

/**
 * 목표 비중이 어디에서 오는가.
 *
 * `USER`면 화면이 002가 확정한 비중을 읽기 전용으로 보이고, `COMPUTED`면 FR-012의 자동 배분
 * 설명을 보인다. **화면이 전략 ID로 분기하지 않게 하려고 등록표가 내려 주는 값이다.**
 */
export type TargetWeightSource = 'USER' | 'EQUAL' | 'COMPUTED'

/** 축이 목록형인가 입력형인가. */
export type AxisInputType = 'CHOICE' | 'NUMBER'

/** 유효성 규칙의 비교 연산자. */
export type RuleOperator =
  | 'GREATER_THAN'
  | 'GREATER_OR_EQUAL'
  | 'LESS_THAN'
  | 'LESS_OR_EQUAL'
  | 'EQUAL'
  | 'NOT_EQUAL'

/** 규칙이 참조하는 맥락 키. 지금은 후보 섹터 집합의 크기 하나다. */
export type RuleContextKey = 'UNIVERSE_SIZE'

/**
 * 유효성 규칙의 피연산자. 축 참조·맥락 참조·상수 셋 중 하나다.
 *
 * 축 참조에는 `multiplyByContext`를 덧붙여 곱할 수 있다(비중 상한 × 섹터 수).
 */
export type Operand =
  | { axis: string; multiplyByContext?: RuleContextKey }
  | { context: RuleContextKey }
  | { value: ScalarValue }

/**
 * 화면이 실행 전에 평가하는 조건.
 *
 * **조건이 참이면 위반이다** — 그 설정으로 분석을 시작하지 않는다. `detail`은 사용자에게 보일
 * 한 문장이며 **화면이 문구를 만들지 않는다.**
 */
export type ValidationRule = {
  code: string
  detail: string
  left: Operand
  operator: RuleOperator
  right: Operand
}

/**
 * 워밍업 산출 규칙.
 *
 * `sum` 배열의 항목이 축 이름이면 그 축의 현재 값으로 바꿔 더하고, 숫자면 그대로 더한다.
 * 규칙이 없는 전략은 워밍업이 0이다.
 */
export type WarmupRule = {
  sum: (string | number)[]
}

/** 목록형 축의 후보 하나. `label`은 등록표가 소유하며 화면이 만들지 않는다. */
export type ChoiceView = {
  value: AxisValue
  label: string
  /** `false`면 유효성 제약에 걸린 값이다. 목록에서 빼지 않고 흐리게 보인다. */
  selectable: boolean
}

/** 입력형 축의 허용 범위. */
export type RangeView = {
  min: number
  max: number
  step: number
}

/**
 * 설정 항목 하나.
 *
 * `description`은 **화면이 축 이름으로 분기해 설명을 고르지 않게 하는 자리다**(research R7-1).
 * FR-019의 상한 재배분 안내 문장도 여기에 온다.
 */
export type AxisView = {
  name: string
  label: string
  description?: string
  unit?: string
  inputType: AxisInputType
  /** `inputType`이 `CHOICE`일 때만 실린다. */
  choices?: ChoiceView[]
  /** `inputType`이 `NUMBER`일 때만 실린다. */
  range?: RangeView
  /** 등록표의 기본 시작값. 반드시 `choices` 또는 `range` 안의 값이다. */
  defaultValue: AxisValue
}

/**
 * 전략 카드 하나가 읽는 내용 전부.
 *
 * `selectable`은 **기본 설정 전체를 모든 규칙으로 판정한 결과**이고, 축 후보의 `selectable`은
 * 그 축만 바꿔 그 축을 참조하는 규칙만 본 결과다. 둘은 서로 다른 판정이다.
 */
export type StrategyView = {
  strategyId: string
  name: string
  description: string
  targetWeightSource: TargetWeightSource
  selectable: boolean
  /** `selectable`이 false일 때만 실린다. 화면이 그대로 한 문장으로 보인다. */
  unselectableReason?: string
  /** 기본 설정 기준 워밍업. 카드 목록의 판정에 쓴다. */
  warmupTradingDays: number
  axes: AxisView[]
  validationRules: ValidationRule[]
  /** 워밍업이 없는 전략에는 실리지 않는다. */
  warmupRule?: WarmupRule
}

/** 입력형 분석 조건. 범위와 기본값을 한 덩이로 둔다. */
export type NumericCondition = {
  min: number
  max: number
  step: number
  default: number
  unit?: string
  /** 이 조건을 쓰는 시나리오. `targetVolatility`만 갖는다(004 PD-18). */
  usedBy?: string
}

/** 목록형 분석 조건의 후보. 전략 축의 후보와 달리 `selectable`이 없다. */
export type ConditionChoice = {
  value: ScalarValue
  label: string
}

/**
 * 목록형 분석 조건.
 *
 * `minEvaluationYears`의 `0`이 "0년"이 아니라 "제한 없음"이라는 것을 이 `label`이 말한다
 * (FR-011a). 화면이 그 대응을 코드에 적지 않는다.
 */
export type ChoiceCondition = {
  choices: ConditionChoice[]
  default: ScalarValue
  unit?: string
}

/** 전략과 무관하게 분석 요청 전체에 걸리는 조건들. */
export type AnalysisConditions = {
  initialAmount: NumericCondition
  /** '목표 위험 맞춤' 시나리오에서만 쓴다. 주 분석 화면에 보이지 않는다(004 PD-18). */
  targetVolatility: NumericCondition
  minEvaluationYears: ChoiceCondition
}

/**
 * `GET /strategies/registry` 응답 본문(`ApiResponse`의 `data`).
 *
 * `provisional`이 true면 출처가 있는 기존 임시값 또는 합성 픽스처이며, 화면과 분석 결과가 그
 * 사실을 계속 표시하고 확정값으로 표현하지 않는다.
 */
export type RegistryResponse = {
  registryVersion: string
  provisional: boolean
  analysisConditions: AnalysisConditions
  strategies: StrategyView[]
}
