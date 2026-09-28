import { useCallback, useEffect, useMemo, useState } from "react";
import type {
  AxisValue,
  AxisView,
  RegistryResponse,
  StrategyView,
} from "@/types/strategyRegistry";
import { isIsoDate } from "@/utils/analysisPeriod";
import { evaluateWarmup } from "@/utils/warmupRule";

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
export const STRATEGY_SETTINGS_STORAGE_KEY = "thorp.strategySettings";

/** 보관 형식의 번호. 형식을 바꾸면 올린다. 아는 값보다 크면 읽지 않는다. */
export const STRATEGY_SETTINGS_PAYLOAD_VERSION = 1;

/** 축 이름 → 현재 값. 비워 둔 축은 키 자체가 없다. */
export type AxisValues = Record<string, AxisValue>;

/**
 * 분석 조건(data-model §9).
 *
 * <p><b>`periodStart`·`periodEnd`·`initialAmount`는 사용자가 바꿨을 때만 담는다.</b> 없으면 화면은 기간을
 * 001의 평가 가능 범위 전체로, 금액을 등록표 `default`로 쓴다(T067). 처음 값을 상태에 복사해 두지 않는
 * 까닭은 평가 가능 범위가 고른 전략과 워밍업에 따라 바뀌기 때문이다 — 복사해 두면 사용자가 고른 적 없는
 * 옛 범위가 "사용자가 바꾼 기간"처럼 남는다. 등록표 기본값도 같은 이유로 복사하지 않는다.
 *
 * <p>기간은 두 날짜가 늘 함께 있거나 함께 없다.
 */
export type AnalysisCondition = {
  /** 등록표 `analysisConditions.minEvaluationYears` 후보 중 하나. `0`은 제한 없음이다(FR-011a) */
  minEvaluationYears: number;
  /**
   * 연속 운용이 기본이다. `targetVolatility`가 있을 때만 변동성 제한(004 `TARGET_RISK`)이고, 이 값은 늘
   * 그것에서 정해진다 — 따로 바꾸지 않는다.
   */
  scenario: "FULL_PERIOD" | "TARGET_RISK";
  /** 변동성 제한의 목표 변동성(연, 퍼센트 숫자). 등록표 `analysisConditions.targetVolatility` 범위 안이다 */
  targetVolatility?: number;
  periodStart?: string;
  periodEnd?: string;
  initialAmount?: number;
};

export type InvalidStoredValueReason =
  /** 보관된 전략이 현재 등록표에 없다. 값 전체를 지웠다 */
  | "STRATEGY_REMOVED"
  /** 보관된 항목이 현재 등록표의 그 전략에 없다. 값을 지웠다 */
  | "AXIS_REMOVED"
  /** 목록형 항목인데 후보에 없는 값이다 */
  | "NOT_IN_CHOICES"
  /** 후보이긴 하나 지금 조건에서 고를 수 없다(`choices[].selectable: false`) */
  | "CHOICE_UNSELECTABLE"
  /** 입력형 항목인데 최소·최대 밖이거나 숫자가 아니다 */
  | "OUT_OF_RANGE"
  /** 입력형 항목인데 등록된 간격에 맞지 않는다 */
  | "OFF_STEP"
  /** 최소 평가 기간의 보관값이 등록표 후보에 없어 기본값으로 시작했다 */
  | "CONDITION_NOT_IN_CHOICES"
  /** 보관된 분석 기간을 날짜로 읽을 수 없어 지웠다 */
  | "PERIOD_UNREADABLE";

/**
 * 보관되어 있었으나 현재 등록표에서 쓸 수 없는 값 하나.
 *
 * `restorable`이면 그 축은 비어 있고 `resetAxis`로 기본값을 넣을 수 있다. 아니면 이미 지운
 * 값이라 알리기만 한다.
 */
export type InvalidStoredValue = {
  /** `null`이면 전략과 무관한 분석 조건이다 */
  strategyId: string | null;
  /** `null`이면 전략 전체가 사라진 경우다 */
  axisName: string | null;
  storedValue: unknown;
  reason: InvalidStoredValueReason;
  /** 사용자에게 보일 한 문장. 등록표의 라벨·단위·범위를 그대로 쓴다 */
  message: string;
  restorable: boolean;
};

/** `sessionStorage`에 두는 모양. 등록표 버전·섹터 수를 함께 담아 재검증의 근거로 삼는다. */
export type StoredStrategySettings = {
  registryVersion: string;
  universeSize: number | null;
  selectedStrategyId: string | null;
  valuesByStrategy: Record<string, AxisValues>;
  analysisCondition: AnalysisCondition;
  invalidValues: InvalidStoredValue[];
};

// ---------------------------------------------------------------------------
// 값 비교·판정 — 축 이름이나 전략 ID로 분기하지 않는다(research R7-1)
// ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isScalar(value: unknown): value is string | number {
  return (
    typeof value === "string" ||
    (typeof value === "number" && Number.isFinite(value))
  );
}

/** 홑값이거나 홑값만 담은 한 겹 객체인가(`types/strategyRegistry.ts`의 `AxisValue`). */
export function isAxisValue(value: unknown): value is AxisValue {
  if (isScalar(value)) return true;
  if (!isRecord(value)) return false;
  return Object.values(value).every(isScalar);
}

/**
 * 축 값 둘이 같은가. 합성 값(리밸런싱 주기)은 칸마다 견준다.
 *
 * 칸의 뜻을 읽지 않는다 — 같은 키에 같은 값이면 같은 것이다. 서버의 `ParameterAxis.indexOf`가
 * `equals`로만 비교하는 것과 같은 규약이다(data-model §5).
 */
export function axisValueEquals(left: AxisValue, right: AxisValue): boolean {
  if (typeof left !== "object" || typeof right !== "object")
    return left === right;
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  if (leftKeys.length !== rightKeys.length) return false;
  return leftKeys.every((key) => key in right && left[key] === right[key]);
}

/** `String(n)`이 지수 표기로 바뀌지 않는 범위에서 소수 자릿수를 센다. */
function decimalPlaces(value: number): number {
  const text = String(value);
  if (text.includes("e")) return 10;
  const point = text.indexOf(".");
  return point === -1 ? 0 : text.length - point - 1;
}

/**
 * 값이 `min`에서 `step` 간격 위에 놓이는가.
 *
 * <p><b>부동소수점 나머지 연산을 쓰지 않는다.</b> `(0.3 - 0.1) % 0.1`은 0이 아니다. 등록표의
 * `step`을 소수 문자열로 보고 셋을 같은 자릿수의 정수로 올려 견준다(spec Edge Cases).
 */
export function isOnStep(value: number, min: number, step: number): boolean {
  const places = Math.min(
    10,
    Math.max(decimalPlaces(value), decimalPlaces(min), decimalPlaces(step)),
  );
  const scale = 10 ** places;
  const scaled = (n: number) => Math.round(n * scale);
  const stepScaled = scaled(step);
  if (stepScaled <= 0) return true;
  return (scaled(value) - scaled(min)) % stepScaled === 0;
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
):
  | "NOT_IN_CHOICES"
  | "CHOICE_UNSELECTABLE"
  | "OUT_OF_RANGE"
  | "OFF_STEP"
  | null {
  if (axis.inputType === "CHOICE") {
    if (!isAxisValue(value)) return "NOT_IN_CHOICES";
    const choice = (axis.choices ?? []).find((candidate) =>
      axisValueEquals(candidate.value, value),
    );
    if (!choice) return "NOT_IN_CHOICES";
    return choice.selectable ? null : "CHOICE_UNSELECTABLE";
  }

  if (!axis.range) return "OUT_OF_RANGE";
  return checkNumberInRange(value, axis.range);
}

/**
 * 입력형 값 하나가 최소·최대 안이고 간격에 맞는가.
 *
 * 전략의 입력형 축과 분석 조건의 입력형 값(시작 금액)이 **같은 규칙**을 쓴다. 따로 두면 한쪽만
 * 간격 판정을 빠뜨리는 일이 생긴다. 최솟값·최댓값과 정확히 같은 값은 허용한다(spec Edge Cases).
 */
export function checkNumberInRange(
  value: unknown,
  range: { min: number; max: number; step: number },
): "OUT_OF_RANGE" | "OFF_STEP" | null {
  if (typeof value !== "number" || !Number.isFinite(value))
    return "OUT_OF_RANGE";
  if (value < range.min || value > range.max) return "OUT_OF_RANGE";
  return isOnStep(value, range.min, range.step) ? null : "OFF_STEP";
}

/**
 * 사용자가 기본값 복원으로 되돌릴 수 있는 분석 조건.
 *
 * <p>최소 평가 기간은 목록형이라 후보에 없으면 기본값으로 시작하고 알리기만 한다(되돌릴 값이 이미
 * 기본값이다).
 *
 * <p><b>분석 기간은 이 재검증이 범위를 판정하지 않는다.</b> 범위는 001의 평가 가능 범위이고 고른 전략과
 * 워밍업마다 달라서, 등록표를 받는 시점에는 알 수 없다. 여기서는 날짜로 읽히는지만 보고, 범위 밖인지는
 * 화면이 판정한 범위를 받을 때마다 본다(`StrategySettingsPage`). `period`를 복원하면 두 날짜를 함께 지워
 * 평가 가능 범위 전체로 돌아간다.
 */
export type RestorableCondition = "initialAmount" | "period";

/** 시작 금액을 가리키는 표시 이름. 요약 줄의 항목 이름과 같게 둔다. */
const INITIAL_AMOUNT_LABEL = "시작 금액";

function displayValue(value: unknown): string {
  if (isScalar(value)) return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/** `checkAxisValue`가 돌려줄 수 있는 이유만 모은 것. 전략·항목이 사라진 경우는 축이 없어 여기 없다. */
type AxisProblemReason = NonNullable<ReturnType<typeof checkAxisValue>>;

/** 판정 이유를 사용자에게 보일 한 문장으로. 라벨·단위·범위는 등록표에서 그대로 읽는다. */
function describeAxisProblem(
  axis: AxisView,
  value: unknown,
  reason: AxisProblemReason,
): string {
  const shown = displayValue(value);
  const unit = axis.unit ?? "";
  switch (reason) {
    case "NOT_IN_CHOICES":
      return `'${axis.label}'의 저장값 ${shown}은(는) 현재 등록표의 후보에 없습니다.`;
    case "CHOICE_UNSELECTABLE":
      return `'${axis.label}'의 저장값 ${shown}은(는) 지금 조건에서는 고를 수 없는 후보입니다.`;
    case "OUT_OF_RANGE":
      return axis.range
        ? `'${axis.label}'의 저장값 ${shown}은(는) 허용 범위(${axis.range.min}~${axis.range.max}${unit}) 밖입니다.`
        : `'${axis.label}'의 저장값 ${shown}은(는) 허용 범위 밖입니다.`;
    case "OFF_STEP":
      return `'${axis.label}'의 저장값 ${shown}은(는) 등록된 간격(${axis.range?.step ?? ""}${unit})에 맞지 않습니다.`;
  }
}

// ---------------------------------------------------------------------------
// 보관·복원
// ---------------------------------------------------------------------------

function isInvalidStoredValue(value: unknown): value is InvalidStoredValue {
  if (!isRecord(value)) return false;
  return (
    (value.strategyId === null || typeof value.strategyId === "string") &&
    (value.axisName === null || typeof value.axisName === "string") &&
    typeof value.reason === "string" &&
    typeof value.message === "string" &&
    typeof value.restorable === "boolean"
  );
}

/**
 * 보관된 값을 읽는다. 모양이 어긋나면 통째로 버리고 `null`이다 — 일부만 살려 쓰면 어느 값이
 * 사용자의 것인지 알 수 없다. 저장소가 막혀 있어도 예외를 올리지 않는다.
 */
export function readStoredStrategySettings(): StoredStrategySettings | null {
  let raw: string | null;
  try {
    raw = window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;

  let payload: unknown;
  try {
    payload = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(payload)) return null;
  if (payload.version !== STRATEGY_SETTINGS_PAYLOAD_VERSION) return null;
  if (typeof payload.registryVersion !== "string") return null;
  if (payload.universeSize !== null && typeof payload.universeSize !== "number")
    return null;
  if (
    payload.selectedStrategyId !== null &&
    typeof payload.selectedStrategyId !== "string"
  ) {
    return null;
  }
  if (!isRecord(payload.valuesByStrategy)) return null;
  if (!isRecord(payload.analysisCondition)) return null;

  const valuesByStrategy: Record<string, AxisValues> = {};
  for (const [strategyId, values] of Object.entries(payload.valuesByStrategy)) {
    if (!isRecord(values)) return null;
    const entries: AxisValues = {};
    for (const [axisName, value] of Object.entries(values)) {
      // 모양이 어긋난 값은 재검증에서 걸리도록 그대로 넘기지 않고 여기서 페이로드를 버린다.
      // 어떤 값이었는지 말할 수 없는 것을 "유효하지 않다"고 알릴 수는 없기 때문이다.
      if (!isAxisValue(value)) return null;
      entries[axisName] = value;
    }
    valuesByStrategy[strategyId] = entries;
  }

  const condition = payload.analysisCondition;
  if (typeof condition.minEvaluationYears !== "number") return null;
  const analysisCondition: AnalysisCondition = {
    minEvaluationYears: condition.minEvaluationYears,
    scenario: "FULL_PERIOD",
  };
  if (typeof condition.periodStart === "string")
    analysisCondition.periodStart = condition.periodStart;
  if (typeof condition.periodEnd === "string")
    analysisCondition.periodEnd = condition.periodEnd;
  if (typeof condition.initialAmount === "number") {
    analysisCondition.initialAmount = condition.initialAmount;
  }
  if (typeof condition.targetVolatility === "number") {
    analysisCondition.targetVolatility = condition.targetVolatility;
  }

  const invalidValues = Array.isArray(payload.invalidValues)
    ? payload.invalidValues.filter(isInvalidStoredValue)
    : [];

  return {
    registryVersion: payload.registryVersion,
    universeSize: payload.universeSize,
    selectedStrategyId: payload.selectedStrategyId,
    valuesByStrategy,
    analysisCondition,
    invalidValues,
  };
}

/**
 * 보관한다. 보관에 성공했는지를 돌려준다.
 *
 * 내 기록의 '설정 바꿔 비교'가 결과의 조건을 이 화면에 넘길 때도 쓴다. 넘기는 수단이 이 저장소
 * 항목뿐이라, 보관이 실패하면 넘기는 쪽이 그 사실을 알아야 사용자를 빈 설정 화면으로 보내지 않는다. 이
 * 화면 안에서는 결과를 쓰지 않는다 — 실패해도 화면 상태로 계속 동작한다.
 */
export function writeStoredStrategySettings(
  state: StoredStrategySettings,
): boolean {
  try {
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({ version: STRATEGY_SETTINGS_PAYLOAD_VERSION, ...state }),
    );
    return true;
  } catch {
    // 저장소가 막힌 환경에서는 화면 상태로만 동작한다. 화면을 오류로 막지 않는다(research R8).
    return false;
  }
}

// ---------------------------------------------------------------------------
// 재검증
// ---------------------------------------------------------------------------

/** 지금 값에서 이 축을 쓰는가. 조건이 없으면 늘 참이고, 결정 축의 값이 없으면 거짓이다(계약 §1 `appliesWhen`). */
export function axisApplies(axis: AxisView, values: AxisValues): boolean {
  if (!axis.appliesWhen) return true;
  const decider = values[axis.appliesWhen.axis];
  return (
    decider !== undefined && axisValueEquals(decider, axis.appliesWhen.value)
  );
}

/**
 * 지금 값에서 뜻을 갖는 축만, 등록표 순서대로.
 *
 * 화면이 그리는 것, 요청에 싣는 것, 완성·기본 여부 판정이 모두 이 하나를 쓴다. 각자 따로 판정하면 한쪽만
 * 조건을 빠뜨려 화면이 계약대로 보낸 요청이 서버에서 막힌다(FR-003).
 */
export function applicableAxes(
  strategy: StrategyView,
  values: AxisValues,
): AxisView[] {
  return strategy.axes.filter((axis) => axisApplies(axis, values));
}

/**
 * 조건에 맞게 값을 정돈한다. 조건 밖 축의 값은 지우고, 조건이 참인데 값이 없는 축은 기본값으로 채운다.
 *
 * <p>`keepEmpty`에 든 축은 채우지 않는다 — 재검증이 비워 두고 사용자의 복원을 기다리는 축이다. 결정 축이
 * 바뀌면 다른 축의 조건이 함께 바뀌므로 더 바뀌는 것이 없을 때까지 되풀이한다.
 */
export function settleValues(
  strategy: StrategyView,
  values: AxisValues,
  keepEmpty: ReadonlySet<string> = new Set(),
): AxisValues {
  let current: AxisValues = { ...values };
  for (;;) {
    const applicable = new Set(
      applicableAxes(strategy, current).map((axis) => axis.name),
    );
    let changed = false;
    const next: AxisValues = {};
    for (const [name, value] of Object.entries(current)) {
      if (applicable.has(name)) next[name] = value;
      else changed = true;
    }
    for (const axis of strategy.axes) {
      if (
        !applicable.has(axis.name) ||
        axis.name in next ||
        keepEmpty.has(axis.name)
      )
        continue;
      next[axis.name] = axis.defaultValue;
      changed = true;
    }
    current = next;
    if (!changed) return current;
  }
}

/**
 * 전략의 기본 설정. 추천이 아니라 편의용 출발점이다(FR-004).
 *
 * 조건 밖 축은 넣지 않는다 — 기본 선정 방식이 가리키지 않는 값은 사용자가 정한 적도 규칙이 볼 것도 아니다.
 */
export function defaultValuesOf(strategy: StrategyView): AxisValues {
  return settleValues(strategy, {});
}

function toNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Number(value))
  ) {
    return Number(value);
  }
  return null;
}

/** 등록표가 정한 최소 평가 기간 후보(숫자만). 화면이 `0`·`1`·`3`을 코드에 적지 않는다. */
function minEvaluationYearChoices(registry: RegistryResponse): number[] {
  return registry.analysisConditions.minEvaluationYears.choices
    .map((choice) => toNumber(choice.value))
    .filter((value): value is number => value !== null);
}

function minEvaluationYearsDefault(registry: RegistryResponse): number {
  const condition = registry.analysisConditions.minEvaluationYears;
  return (
    toNumber(condition.default) ??
    minEvaluationYearChoices(registry)[0] ??
    Number.NaN
  );
}

function minEvaluationYearsLabel(
  registry: RegistryResponse,
  value: number,
): string {
  const choice = registry.analysisConditions.minEvaluationYears.choices.find(
    (candidate) => toNumber(candidate.value) === value,
  );
  return choice?.label ?? String(value);
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
      scenario: "FULL_PERIOD",
    },
    invalidValues: [],
  };
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
  if (stored === null) return freshSettings(registry, universeSize);

  const strategies = new Map(
    registry.strategies.map((strategy) => [strategy.strategyId, strategy]),
  );
  const invalidValues: InvalidStoredValue[] = [];
  const valuesByStrategy: Record<string, AxisValues> = {};

  // 앞선 판정에서 비워 둔 항목. 아직 복원되지 않았으면 그대로 옮긴다.
  const pending = stored.invalidValues.filter(
    (entry) =>
      entry.restorable && entry.strategyId !== null && entry.axisName !== null,
  );
  const isPending = (strategyId: string, axisName: string) =>
    pending.some(
      (entry) => entry.strategyId === strategyId && entry.axisName === axisName,
    );

  for (const [strategyId, values] of Object.entries(stored.valuesByStrategy)) {
    const strategy = strategies.get(strategyId);
    if (!strategy) {
      invalidValues.push({
        strategyId,
        axisName: null,
        storedValue: values,
        reason: "STRATEGY_REMOVED",
        message: `저장된 전략 '${strategyId}'의 설정은 현재 등록표에 그 전략이 없어 지웠습니다.`,
        restorable: false,
      });
      continue;
    }

    const axes = new Map(strategy.axes.map((axis) => [axis.name, axis]));
    const next: AxisValues = {};

    for (const [axisName, value] of Object.entries(values)) {
      const axis = axes.get(axisName);
      if (!axis) {
        invalidValues.push({
          strategyId,
          axisName,
          storedValue: value,
          reason: "AXIS_REMOVED",
          message: `'${strategy.name}'의 저장된 항목 '${axisName}'은(는) 현재 등록표에 없어 지웠습니다.`,
          restorable: false,
        });
        continue;
      }

      const reason = checkAxisValue(axis, value);
      if (reason === null) {
        next[axisName] = value;
        continue;
      }
      // 비워 둔다. 기본값을 대신 넣지 않는다.
      invalidValues.push({
        strategyId,
        axisName,
        storedValue: value,
        reason,
        message: describeAxisProblem(axis, value, reason),
        restorable: true,
      });
    }

    // 채우지 않고 비워 둘 축: 방금 무효로 판정한 축과 앞서 비워 둔 채 복원을 기다리는 축.
    const emptied = new Set(
      invalidValues
        .filter(
          (entry) =>
            entry.strategyId === strategyId &&
            entry.axisName !== null &&
            entry.restorable,
        )
        .map((entry) => entry.axisName as string),
    );
    for (const axis of strategy.axes) {
      if (axis.name in next || !isPending(strategyId, axis.name)) continue;
      const entry = pending.find(
        (candidate) =>
          candidate.strategyId === strategyId &&
          candidate.axisName === axis.name,
      );
      if (entry) {
        invalidValues.push(entry);
        emptied.add(axis.name);
      }
    }

    // 조건 밖 축의 값은 지우고, 조건이 참인데 사용자 값이 없던 자리(등록표에 새로 생긴 항목 등)는
    // 기본값으로 채운다. 비워 둔 축은 채우지 않는다.
    const settled = settleValues(strategy, next, emptied);
    valuesByStrategy[strategyId] = settled;

    // 조건 밖으로 밀려난 축의 무효 안내는 지운다 — 그리지도 싣지도 않는 축을 고치라고 할 수 없다.
    const visible = new Set(
      applicableAxes(strategy, settled).map((axis) => axis.name),
    );
    for (let index = invalidValues.length - 1; index >= 0; index -= 1) {
      const entry = invalidValues[index];
      if (
        entry.strategyId === strategyId &&
        entry.axisName !== null &&
        entry.restorable &&
        !visible.has(entry.axisName)
      ) {
        invalidValues.splice(index, 1);
      }
    }
  }

  const conditionChoices = minEvaluationYearChoices(registry);
  let minEvaluationYears = stored.analysisCondition.minEvaluationYears;
  if (!conditionChoices.includes(minEvaluationYears)) {
    const fallback = minEvaluationYearsDefault(registry);
    invalidValues.push({
      strategyId: null,
      axisName: "minEvaluationYears",
      storedValue: minEvaluationYears,
      reason: "CONDITION_NOT_IN_CHOICES",
      message: `최소 평가 기간의 저장값 ${displayValue(minEvaluationYears)}은(는) 현재 등록표 후보에 없어 '${minEvaluationYearsLabel(registry, fallback)}'으로 시작합니다.`,
      restorable: false,
    });
    minEvaluationYears = fallback;
  }

  // --- 시작 금액 ---------------------------------------------------------------------------
  //
  // **축 값과 같은 잣대로 다시 판정한다.** 앞서는 분석 조건을 통째로 옮겨 시작 금액이 판정 없이
  // 살아남았고, 등록표 범위가 좁아졌거나 저장소가 편집된 경우 범위 밖 금액이 화면에 보이고 요청으로
  // 나갔다(SC-002, FR-017).
  //
  // **비우되, 비운 것이 곧 기본값이 되지 않게 한다.** 시작 금액이 없으면 화면은 등록표 기본값을
  // 쓰므로, 비우기만 하면 사용자가 정한 적 없는 기본값으로 조용히 바뀐 것과 같다. 그래서 복원
  // 가능한 안내를 남기고, 화면은 그 안내가 남아 있는 동안 금액을 모르는 것으로 다룬다. 사용자가
  // 복원을 눌러야 안내가 사라지고 기본값이 쓰인다.
  // --- 분석 기간 ---------------------------------------------------------------------------
  //
  // 날짜로 읽을 수 없거나, 한쪽만 있거나, 시작이 종료보다 늦으면 사용자가 고른 기간이 무엇이었는지
  // 말할 수 없다. 둘 다 지우고 알린다 — 지우면 평가 가능 범위 전체가 쓰이므로 알리지 않으면 조용히
  // 바뀐 것과 같다(FR-017). 되돌릴 값이 곧 지운 상태라 복원 버튼은 두지 않는다.
  const {
    initialAmount: storedAmount,
    periodStart: storedStart,
    periodEnd: storedEnd,
    targetVolatility: storedVolatility,
    ...otherConditions
  } = stored.analysisCondition;
  // 변동성 제한은 켜 둔 값이 지금 등록표 범위에 맞을 때만 이어 간다. 맞지 않으면 끈 채로 시작하고 알린다.
  let targetVolatility: number | undefined = storedVolatility;
  const volatilityReason =
    storedVolatility === undefined
      ? null
      : checkNumberInRange(
          storedVolatility,
          registry.analysisConditions.targetVolatility,
        );
  if (volatilityReason !== null) {
    invalidValues.push({
      strategyId: null,
      axisName: "targetVolatility",
      storedValue: storedVolatility,
      reason: volatilityReason,
      message: `변동성 제한의 저장값 연 ${displayValue(storedVolatility)}%는 현재 허용 범위 밖이라 끈 채로 시작합니다.`,
      restorable: false,
    });
    targetVolatility = undefined;
  }
  let period: { periodStart: string; periodEnd: string } | null = null;
  if (storedStart !== undefined || storedEnd !== undefined) {
    if (
      isIsoDate(storedStart) &&
      isIsoDate(storedEnd) &&
      storedStart <= storedEnd
    ) {
      period = { periodStart: storedStart, periodEnd: storedEnd };
    } else {
      invalidValues.push({
        strategyId: null,
        axisName: "period",
        storedValue: { periodStart: storedStart, periodEnd: storedEnd },
        reason: "PERIOD_UNREADABLE",
        message:
          "저장된 분석 기간을 읽을 수 없어 지웠습니다. 평가 가능한 범위 전체로 시작합니다.",
        restorable: false,
      });
    }
  }

  let initialAmount: number | undefined = storedAmount;
  if (storedAmount !== undefined) {
    const range = registry.analysisConditions.initialAmount;
    const reason = checkNumberInRange(storedAmount, range);
    if (reason !== null) {
      const unit = range.unit ?? "";
      invalidValues.push({
        strategyId: null,
        axisName: "initialAmount",
        storedValue: storedAmount,
        reason,
        message:
          reason === "OUT_OF_RANGE"
            ? `'${INITIAL_AMOUNT_LABEL}'의 저장값 ${displayValue(storedAmount)}${unit}은(는) 허용 범위(${range.min}~${range.max}${unit}) 밖입니다.`
            : `'${INITIAL_AMOUNT_LABEL}'의 저장값 ${displayValue(storedAmount)}${unit}은(는) 등록된 간격(${range.step}${unit})에 맞지 않습니다.`,
        restorable: true,
      });
      initialAmount = undefined;
    }
  } else {
    // 앞선 판정에서 비워 두고 아직 복원하지 않았으면 그 안내를 그대로 옮긴다. 옮기지 않으면
    // 새로 고치는 순간 안내가 사라지고 기본값이 조용히 쓰인다.
    const pendingAmount = stored.invalidValues.find(
      (entry) =>
        entry.strategyId === null &&
        entry.axisName === "initialAmount" &&
        entry.restorable,
    );
    if (pendingAmount) invalidValues.push(pendingAmount);
  }

  const selectedStrategyId =
    stored.selectedStrategyId !== null &&
    strategies.has(stored.selectedStrategyId)
      ? stored.selectedStrategyId
      : null;

  const analysisCondition: AnalysisCondition = {
    ...otherConditions,
    minEvaluationYears,
    scenario: targetVolatility === undefined ? "FULL_PERIOD" : "TARGET_RISK",
  };
  if (targetVolatility !== undefined)
    analysisCondition.targetVolatility = targetVolatility;
  if (initialAmount !== undefined)
    analysisCondition.initialAmount = initialAmount;
  if (period !== null) Object.assign(analysisCondition, period);

  return {
    registryVersion: registry.registryVersion,
    universeSize,
    selectedStrategyId,
    valuesByStrategy,
    analysisCondition,
    invalidValues,
  };
}

/** 전략과 무관한 분석 조건 하나의 무효 안내를 뺀다. */
function withoutCondition(
  entries: InvalidStoredValue[],
  name: RestorableCondition,
): InvalidStoredValue[] {
  return entries.filter(
    (entry) => !(entry.strategyId === null && entry.axisName === name),
  );
}

// ---------------------------------------------------------------------------
// 훅
// ---------------------------------------------------------------------------

export type StrategySettings = {
  /** 등록표를 받아 보관값 재검증까지 마쳤는가. 그 전에는 고르기·바꾸기가 무시된다 */
  ready: boolean;
  registryVersion: string | null;
  selectedStrategyId: string | null;
  selectedStrategy: StrategyView | null;
  /** 선택 전략의 축 값. 비워 둔 축은 키가 없다 */
  values: AxisValues | null;
  valuesByStrategy: Record<string, AxisValues>;
  analysisCondition: AnalysisCondition;
  invalidValues: InvalidStoredValue[];
  /** 선택 전략의 현재 설정으로 계산한 워밍업. 축이 비어 있거나 계산할 수 없으면 `null` */
  warmupTradingDays: number | null;
  /** 모든 축에 값이 있는가. 비워 둔 축이 있으면 분석을 시작할 수 없다 */
  isComplete: (strategyId: string) => boolean;
  select: (strategyId: string) => void;
  /** 고른 전략을 내려놓는다. 전략별로 바꿔 둔 값은 지우지 않는다 — 다시 고르면 그대로 돌아온다 */
  clearSelection: () => void;
  /** 허용 범위 안이면 반영하고 `null`, 밖이면 반영하지 않고 이유를 돌려준다(FR-006) */
  setValue: (
    strategyId: string,
    axisName: string,
    value: AxisValue,
  ) => InvalidStoredValueReason | null;
  resetAxis: (strategyId: string, axisName: string) => void;
  resetAll: (strategyId: string) => void;
  setAnalysisCondition: (
    patch: Partial<Omit<AnalysisCondition, "scenario">>,
  ) => void;
  /** 재검증이 비워 둔 분석 조건을 등록표 기본값으로 되돌리고 그 안내를 지운다 */
  resetAnalysisCondition: (name: RestorableCondition) => void;
};

/**
 * @param registry 조회한 등록표. 아직 없으면 `null`
 * @param universeSize 002가 확정한 섹터 수. 아직 모르면 `null`. 바뀌면 보관값을 다시 판정한다
 */
export function useStrategySettings(
  registry: RegistryResponse | null,
  universeSize: number | null,
): StrategySettings {
  const [state, setState] = useState<StoredStrategySettings | null>(
    readStoredStrategySettings,
  );

  const ready =
    state !== null &&
    registry !== null &&
    state.registryVersion === registry.registryVersion &&
    state.universeSize === universeSize;

  // 등록표가 오거나 버전·섹터 수가 바뀌면 보관값을 다시 판정한다.
  useEffect(() => {
    if (registry === null) return;
    setState((current) =>
      reconcileStoredSettings(current, registry, universeSize),
    );
  }, [registry, universeSize]);

  // 판정을 마친 상태만 보관한다. 판정 전의 값은 이미 보관되어 있던 것이다.
  useEffect(() => {
    if (ready && state !== null) writeStoredStrategySettings(state);
  }, [ready, state]);

  const strategies = useMemo(
    () =>
      new Map(
        (registry?.strategies ?? []).map((strategy) => [
          strategy.strategyId,
          strategy,
        ]),
      ),
    [registry],
  );

  const selectedStrategy =
    ready && state.selectedStrategyId !== null
      ? (strategies.get(state.selectedStrategyId) ?? null)
      : null;
  const values =
    ready && state.selectedStrategyId !== null
      ? (state.valuesByStrategy[state.selectedStrategyId] ?? null)
      : null;

  const isComplete = useCallback(
    (strategyId: string) => {
      if (!ready) return false;
      const strategy = strategies.get(strategyId);
      if (!strategy) return false;
      const current =
        state.valuesByStrategy[strategyId] ?? defaultValuesOf(strategy);
      return applicableAxes(strategy, current).every(
        (axis) => axis.name in current,
      );
    },
    [ready, state, strategies],
  );

  const warmupTradingDays = useMemo(() => {
    if (!selectedStrategy || !values) return null;
    if (
      !applicableAxes(selectedStrategy, values).every(
        (axis) => axis.name in values,
      )
    )
      return null;
    try {
      return evaluateWarmup(selectedStrategy.warmupRule, values);
    } catch {
      return null;
    }
  }, [selectedStrategy, values]);

  const select = useCallback(
    (strategyId: string) => {
      const strategy = strategies.get(strategyId);
      if (!strategy) return;
      setState((current) => {
        if (current === null) return current;
        // 처음 고르는 전략은 등록표 기본값으로 채운다. 이미 값이 있으면 그대로 둔다 — 카드를
        // 옮겼다 돌아와도 바꾼 값이 남아야 한다(spec Edge Cases).
        const valuesByStrategy =
          strategyId in current.valuesByStrategy
            ? current.valuesByStrategy
            : {
                ...current.valuesByStrategy,
                [strategyId]: defaultValuesOf(strategy),
              };
        return { ...current, selectedStrategyId: strategyId, valuesByStrategy };
      });
    },
    [strategies],
  );

  const clearSelection = useCallback(() => {
    setState((current) =>
      current === null || current.selectedStrategyId === null
        ? current
        : { ...current, selectedStrategyId: null },
    );
  }, []);

  const withoutInvalid = (
    entries: InvalidStoredValue[],
    strategyId: string,
    axisName: string | null,
  ) =>
    entries.filter(
      (entry) =>
        !(
          entry.strategyId === strategyId &&
          (axisName === null || entry.axisName === axisName)
        ),
    );

  const setValue = useCallback(
    (
      strategyId: string,
      axisName: string,
      value: AxisValue,
    ): InvalidStoredValueReason | null => {
      const axis = strategies
        .get(strategyId)
        ?.axes.find((candidate) => candidate.name === axisName);
      if (!axis) return "AXIS_REMOVED";
      const reason = checkAxisValue(axis, value);
      if (reason !== null) return reason;
      const strategy = strategies.get(strategyId);
      if (!strategy) return "STRATEGY_REMOVED";
      setState((current) => {
        if (current === null) return current;
        // 결정 축이 바뀌면 다른 축의 조건이 함께 바뀐다. 조건 밖으로 밀려난 축은 값과 안내를 지우고,
        // 조건이 참이 된 축은 기본값에서 시작한다(사용자 값이 없던 자리).
        const before = current.valuesByStrategy[strategyId] ?? {};
        const strategyValues = settleValues(strategy, {
          ...before,
          [axisName]: value,
        });
        const hidden = new Set(
          strategy.axes
            .map((candidate) => candidate.name)
            .filter((name) => !(name in strategyValues)),
        );
        return {
          ...current,
          valuesByStrategy: {
            ...current.valuesByStrategy,
            [strategyId]: strategyValues,
          },
          invalidValues: withoutInvalid(
            current.invalidValues,
            strategyId,
            axisName,
          ).filter(
            (entry) =>
              !(
                entry.strategyId === strategyId &&
                entry.axisName !== null &&
                hidden.has(entry.axisName)
              ),
          ),
        };
      });
      return null;
    },
    [strategies],
  );

  const resetAxis = useCallback(
    (strategyId: string, axisName: string) => {
      const strategy = strategies.get(strategyId);
      const axis = strategy?.axes.find(
        (candidate) => candidate.name === axisName,
      );
      if (!strategy || !axis) return;
      setState((current) => {
        if (current === null) return current;
        const strategyValues = settleValues(strategy, {
          ...(current.valuesByStrategy[strategyId] ?? {}),
          [axisName]: axis.defaultValue,
        });
        return {
          ...current,
          valuesByStrategy: {
            ...current.valuesByStrategy,
            [strategyId]: strategyValues,
          },
          invalidValues: withoutInvalid(
            current.invalidValues,
            strategyId,
            axisName,
          ),
        };
      });
    },
    [strategies],
  );

  const resetAll = useCallback(
    (strategyId: string) => {
      const strategy = strategies.get(strategyId);
      if (!strategy) return;
      setState((current) => {
        if (current === null) return current;
        return {
          ...current,
          valuesByStrategy: {
            ...current.valuesByStrategy,
            [strategyId]: defaultValuesOf(strategy),
          },
          invalidValues: withoutInvalid(
            current.invalidValues,
            strategyId,
            null,
          ),
        };
      });
    },
    [strategies],
  );

  const setAnalysisCondition = useCallback(
    (patch: Partial<Omit<AnalysisCondition, "scenario">>) => {
      if (registry === null) return;
      setState((current) => {
        if (current === null) return current;
        const next: AnalysisCondition = {
          ...current.analysisCondition,
          ...patch,
        };
        // 끄기는 `targetVolatility: undefined`로 온다. 범위 밖 값은 받지 않고 이전 값을 둔다.
        if ("targetVolatility" in patch) {
          if (patch.targetVolatility === undefined) delete next.targetVolatility;
          else if (
            checkNumberInRange(
              patch.targetVolatility,
              registry.analysisConditions.targetVolatility,
            ) !== null
          ) {
            if (current.analysisCondition.targetVolatility === undefined)
              delete next.targetVolatility;
            else
              next.targetVolatility = current.analysisCondition.targetVolatility;
          }
        }
        next.scenario =
          next.targetVolatility === undefined ? "FULL_PERIOD" : "TARGET_RISK";
        let invalidValues = current.invalidValues;
        // 등록표 후보에 없는 최소 평가 기간은 반영하지 않는다(FR-006과 같은 규약).
        if (
          patch.minEvaluationYears !== undefined &&
          !minEvaluationYearChoices(registry).includes(patch.minEvaluationYears)
        ) {
          next.minEvaluationYears =
            current.analysisCondition.minEvaluationYears;
        }
        // 분석 기간은 두 날짜를 한 덩이로 본다. 합친 결과가 날짜 둘이 아니거나 시작이 종료보다 늦으면
        // 둘 다 받지 않는다. 평가 가능 범위 안인지는 이 훅이 모르므로 입력하는 화면이 먼저 거른다.
        if ("periodStart" in patch || "periodEnd" in patch) {
          const { periodStart, periodEnd } = next;
          if (
            !isIsoDate(periodStart) ||
            !isIsoDate(periodEnd) ||
            periodStart > periodEnd
          ) {
            delete next.periodStart;
            delete next.periodEnd;
            if (current.analysisCondition.periodStart !== undefined) {
              next.periodStart = current.analysisCondition.periodStart;
            }
            if (current.analysisCondition.periodEnd !== undefined) {
              next.periodEnd = current.analysisCondition.periodEnd;
            }
          } else {
            // 새 기간을 받았으면 "읽을 수 없어 지웠다"는 옛 안내는 지금 기간과 어긋나는 말이 된다.
            invalidValues = withoutCondition(invalidValues, "period");
          }
        }
        // 시작 금액도 보관값 재검증과 같은 잣대로 본다. 입력 경로와 복원 경로가 다른 잣대를 쓰면
        // 한쪽으로 범위 밖 값이 들어온다(SC-002). 받아들인 값은 그 항목의 무효 안내를 지운다.
        if ("initialAmount" in patch) {
          if (
            patch.initialAmount !== undefined &&
            checkNumberInRange(
              patch.initialAmount,
              registry.analysisConditions.initialAmount,
            ) !== null
          ) {
            if (current.analysisCondition.initialAmount === undefined)
              delete next.initialAmount;
            else next.initialAmount = current.analysisCondition.initialAmount;
          } else if (patch.initialAmount !== undefined) {
            invalidValues = withoutCondition(invalidValues, "initialAmount");
          }
        }
        return { ...current, analysisCondition: next, invalidValues };
      });
    },
    [registry],
  );

  const resetAnalysisCondition = useCallback((name: RestorableCondition) => {
    setState((current) => {
      if (current === null) return current;
      // 값을 지워 등록표 기본값이 쓰이게 한다. 사용자가 복원을 눌렀으므로 이제 기본값은 사용자가
      // 고른 값이다 — 재검증이 비워 둔 상태와 달리 안내도 함께 지운다.
      const analysisCondition = { ...current.analysisCondition };
      if (name === "period") {
        delete analysisCondition.periodStart;
        delete analysisCondition.periodEnd;
      } else {
        delete analysisCondition[name];
      }
      return {
        ...current,
        analysisCondition,
        invalidValues: withoutCondition(current.invalidValues, name),
      };
    });
  }, []);

  return {
    ready,
    registryVersion: ready ? state.registryVersion : null,
    selectedStrategyId: ready ? state.selectedStrategyId : null,
    selectedStrategy,
    values,
    valuesByStrategy: ready ? state.valuesByStrategy : {},
    analysisCondition: ready
      ? state.analysisCondition
      : { minEvaluationYears: Number.NaN, scenario: "FULL_PERIOD" },
    invalidValues: ready ? state.invalidValues : [],
    warmupTradingDays,
    isComplete,
    select,
    clearSelection,
    setValue,
    resetAxis,
    resetAll,
    setAnalysisCondition,
    resetAnalysisCondition,
  };
}
