import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  conditionalStrategy,
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
  withConditionalStrategy,
} from "@/test/strategyRegistryFixtures";
import type { RegistryResponse } from "@/types/strategyRegistry";
import {
  STRATEGY_SETTINGS_PAYLOAD_VERSION,
  STRATEGY_SETTINGS_STORAGE_KEY,
  useStrategySettings,
} from "./useStrategySettings";

/**
 * 전략 설정값의 보관과 재검증을 고정한다(FR-002·FR-006·FR-017, quickstart 6·8).
 *
 * <p><b>픽스처의 전략과 축 이름은 등록표에 없는 것이다.</b> 실제 이름을 쓰면 훅이 그 전략을 알고
 * 있어도 검사가 통과해 버린다 — 지어낸 이름이라야 "응답만 보고 판단한다"가 증명된다(FR-005).
 */

const UNIVERSE_SIZE = 3;

function renderSettings(
  registry: RegistryResponse | null = registryFixture,
  universeSize: number | null = UNIVERSE_SIZE,
) {
  return renderHook(
    ({ registry: currentRegistry, universeSize: currentSize }) =>
      useStrategySettings(currentRegistry, currentSize),
    { initialProps: { registry, universeSize } },
  );
}

function store(payload: Record<string, unknown>) {
  window.sessionStorage.setItem(
    STRATEGY_SETTINGS_STORAGE_KEY,
    JSON.stringify({ version: STRATEGY_SETTINGS_PAYLOAD_VERSION, ...payload }),
  );
}

function readStored(): Record<string, unknown> {
  return JSON.parse(
    window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY) ?? "{}",
  );
}

/** 픽스처의 목록형 축 하나와 입력형 축 하나. 이름을 검사 안에 적지 않으려고 여기서 읽는다. */
const choiceAxis = selectableStrategy.axes[0];
const compositeAxis = selectableStrategy.axes[1];
const numberAxis = selectableStrategy.axes[2];

/**
 * 입력형 축의 경계값도 픽스처에서 만든다.
 *
 * 숫자를 검사 안에 적으면 픽스처의 범위가 바뀔 때 검사가 실패하지 않고 <b>뜻만 조용히 달라진다</b> —
 * "범위 밖"으로 적어 둔 값이 어느 날 범위 안이 되어도 아무것도 알려 주지 않는다.
 */
const numberRange = numberAxis.range!;
/** 범위 밖이면서 간격에는 맞는 값. 두 판정이 섞이지 않게 한다. */
const belowMin = numberRange.min - numberRange.step;
/** 범위 안이고 간격에도 맞는, 기본값과 다른 값. */
const withinRange =
  Number(numberAxis.defaultValue) === numberRange.max
    ? numberRange.max - numberRange.step
    : Number(numberAxis.defaultValue) + numberRange.step;
/** 범위 안이지만 간격에 맞지 않는 값. */
const offStep = withinRange - Math.max(1, Math.floor(numberRange.step / 2));

afterEach(() => {
  vi.restoreAllMocks();
  window.sessionStorage.clear();
});

describe("useStrategySettings", () => {
  it("전략을 고르면 축이 등록표 기본값으로 채워진다", () => {
    const { result } = renderSettings();

    act(() => result.current.select(selectableStrategy.strategyId));

    expect(result.current.selectedStrategyId).toBe(
      selectableStrategy.strategyId,
    );
    for (const axis of selectableStrategy.axes) {
      expect(result.current.values?.[axis.name]).toEqual(axis.defaultValue);
    }
  });

  it("값을 하나 바꾸면 그 값이 남고, 모두 되돌리면 기본값으로 돌아온다", () => {
    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));

    const other = choiceAxis.choices!.find(
      (choice) => choice.value !== choiceAxis.defaultValue,
    )!;
    act(() => {
      result.current.setValue(
        selectableStrategy.strategyId,
        choiceAxis.name,
        other.value,
      );
    });

    expect(result.current.values?.[choiceAxis.name]).toEqual(other.value);

    act(() => result.current.resetAll(selectableStrategy.strategyId));
    expect(result.current.values?.[choiceAxis.name]).toEqual(
      choiceAxis.defaultValue,
    );
  });

  it("항목 하나만 되돌릴 수 있다", () => {
    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));

    act(() => {
      result.current.setValue(
        selectableStrategy.strategyId,
        numberAxis.name,
        withinRange,
      );
    });
    expect(result.current.values?.[numberAxis.name]).toBe(withinRange);

    act(() =>
      result.current.resetAxis(selectableStrategy.strategyId, numberAxis.name),
    );
    expect(result.current.values?.[numberAxis.name]).toEqual(
      numberAxis.defaultValue,
    );
  });

  it("허용 범위 밖 값은 반영하지 않고 이유를 돌려준다", () => {
    // FR-006·SC-002. 잘라 넣지 않는다.
    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));

    let reason: string | null = null;
    act(() => {
      reason = result.current.setValue(
        selectableStrategy.strategyId,
        numberAxis.name,
        belowMin,
      );
    });

    expect(reason).toBe("OUT_OF_RANGE");
    expect(result.current.values?.[numberAxis.name]).toEqual(
      numberAxis.defaultValue,
    );
  });

  it("등록된 간격에 맞지 않는 값도 반영하지 않는다", () => {
    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));

    let reason: string | null = null;
    act(() => {
      reason = result.current.setValue(
        selectableStrategy.strategyId,
        numberAxis.name,
        offStep,
      );
    });

    expect(reason).toBe("OFF_STEP");
    expect(result.current.values?.[numberAxis.name]).toEqual(
      numberAxis.defaultValue,
    );
  });

  it("고를 수 없는 후보는 반영하지 않는다", () => {
    const { result } = renderSettings();
    act(() => result.current.select(unselectableStrategy.strategyId));

    const blockedAxis = unselectableStrategy.axes.find((axis) =>
      axis.choices?.some((choice) => !choice.selectable),
    )!;
    const blocked = blockedAxis.choices!.find((choice) => !choice.selectable)!;

    let reason: string | null = null;
    act(() => {
      reason = result.current.setValue(
        unselectableStrategy.strategyId,
        blockedAxis.name,
        blocked.value,
      );
    });

    expect(reason).toBe("CHOICE_UNSELECTABLE");
  });

  it("다른 전략으로 옮겨도 이전 전략 값이 남고 새 전략에 적용되지 않는다", () => {
    // spec Edge Cases.
    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));
    act(() => {
      result.current.setValue(
        selectableStrategy.strategyId,
        numberAxis.name,
        withinRange,
      );
    });

    act(() => result.current.select(unselectableStrategy.strategyId));
    expect(result.current.values).not.toHaveProperty(numberAxis.name);

    act(() => result.current.select(selectableStrategy.strategyId));
    expect(result.current.values?.[numberAxis.name]).toBe(withinRange);
  });

  it("보관한 값을 다음 렌더에서 그대로 읽는다", () => {
    const first = renderSettings();
    act(() => first.result.current.select(selectableStrategy.strategyId));
    act(() => {
      first.result.current.setValue(
        selectableStrategy.strategyId,
        numberAxis.name,
        withinRange,
      );
    });
    first.unmount();

    const second = renderSettings();
    expect(second.result.current.selectedStrategyId).toBe(
      selectableStrategy.strategyId,
    );
    expect(second.result.current.values?.[numberAxis.name]).toBe(withinRange);
  });

  it("등록표 버전이 다르면 유효한 값은 유지하고 유효하지 않은 값만 비운다", () => {
    // FR-017. 자동으로 고치지 않고 이유와 복원 선택을 돌려준다.
    store({
      registryVersion: "OLD-REGISTRY",
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: selectableStrategy.strategyId,
      valuesByStrategy: {
        [selectableStrategy.strategyId]: {
          [choiceAxis.name]: choiceAxis.choices![0].value,
          [numberAxis.name]: 999,
          [compositeAxis.name]: compositeAxis.defaultValue,
        },
      },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.registryVersion).toBe(
      registryFixture.registryVersion,
    );
    // 유효한 값은 그대로 남는다.
    expect(result.current.values?.[choiceAxis.name]).toEqual(
      choiceAxis.choices![0].value,
    );
    // 유효하지 않은 값은 비워 두고 기본값으로 덮지 않는다.
    expect(result.current.values).not.toHaveProperty(numberAxis.name);
    expect(result.current.isComplete(selectableStrategy.strategyId)).toBe(
      false,
    );

    const invalid = result.current.invalidValues.find(
      (entry) => entry.axisName === numberAxis.name,
    );
    expect(invalid?.reason).toBe("OUT_OF_RANGE");
    expect(invalid?.storedValue).toBe(999);
    expect(invalid?.restorable).toBe(true);
    expect(invalid?.message).toContain(numberAxis.label);
  });

  it("옛 SCORE_WEIGHTED 보관값은 새 배분 후보로 몰래 바꾸지 않고 복원을 기다린다", () => {
    const allocationAxis = {
      ...choiceAxis,
      name: "allocationMethod",
      label: "배분 방식",
      choices: [
        { value: "EQUAL", label: "똑같이 나누기", selectable: true },
        {
          value: "INVERSE_VOLATILITY",
          label: "변동성이 낮은 섹터에 더 나누기",
          selectable: true,
        },
      ],
      defaultValue: "EQUAL",
    };
    const strategy = {
      ...selectableStrategy,
      axes: [allocationAxis],
      warmupTradingDays: 0,
      warmupRule: undefined,
    };
    const registry: RegistryResponse = {
      ...registryFixture,
      registryVersion: "1.5.0",
      strategies: [strategy],
    };
    store({
      registryVersion: "1.4.0",
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: strategy.strategyId,
      valuesByStrategy: {
        [strategy.strategyId]: { allocationMethod: "SCORE_WEIGHTED" },
      },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings(registry);

    expect(result.current.values).not.toHaveProperty("allocationMethod");
    expect(result.current.isComplete(strategy.strategyId)).toBe(false);
    expect(result.current.invalidValues).toEqual([
      expect.objectContaining({
        strategyId: strategy.strategyId,
        axisName: "allocationMethod",
        storedValue: "SCORE_WEIGHTED",
        reason: "NOT_IN_CHOICES",
        restorable: true,
      }),
    ]);

    act(() =>
      result.current.resetAxis(strategy.strategyId, "allocationMethod"),
    );
    expect(result.current.values?.allocationMethod).toBe("EQUAL");
    expect(result.current.invalidValues).toHaveLength(0);
  });

  it("비워 둔 값을 복원하면 안내가 사라지고 설정이 완성된다", () => {
    store({
      registryVersion: "OLD-REGISTRY",
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: selectableStrategy.strategyId,
      valuesByStrategy: {
        [selectableStrategy.strategyId]: {
          [choiceAxis.name]: choiceAxis.defaultValue,
          [compositeAxis.name]: compositeAxis.defaultValue,
          [numberAxis.name]: 999,
        },
      },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings();
    expect(result.current.isComplete(selectableStrategy.strategyId)).toBe(
      false,
    );

    act(() =>
      result.current.resetAxis(selectableStrategy.strategyId, numberAxis.name),
    );

    expect(result.current.invalidValues).toHaveLength(0);
    expect(result.current.isComplete(selectableStrategy.strategyId)).toBe(true);
    expect(result.current.values?.[numberAxis.name]).toEqual(
      numberAxis.defaultValue,
    );
  });

  it("섹터 수가 바뀌면 같은 등록표라도 다시 판정한다", () => {
    const { result, rerender } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));
    expect(result.current.ready).toBe(true);

    rerender({ registry: registryFixture, universeSize: UNIVERSE_SIZE + 1 });

    // 판정을 마친 상태의 섹터 수가 새 값으로 바뀌어야 다음 조회 응답의 `selectable`과 어긋나지 않는다.
    expect(result.current.ready).toBe(true);
    expect(readStored().universeSize).toBe(UNIVERSE_SIZE + 1);
  });

  it("등록표에서 사라진 전략의 보관값은 지우고 그 사실을 남긴다", () => {
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: "GONE_STRATEGY",
      valuesByStrategy: { GONE_STRATEGY: { someAxis: 1 } },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.selectedStrategyId).toBeNull();
    expect(result.current.valuesByStrategy).not.toHaveProperty("GONE_STRATEGY");
    const removed = result.current.invalidValues.find(
      (entry) => entry.reason === "STRATEGY_REMOVED",
    );
    expect(removed?.restorable).toBe(false);
  });

  it("최소 평가 기간은 등록표 기본값으로 시작하고 후보 안에서만 바뀐다", () => {
    // FR-011a. 후보와 기본값은 등록표가 소유하며 화면이 숫자를 코드에 적지 않는다.
    const condition = registryFixture.analysisConditions.minEvaluationYears;
    const { result } = renderSettings();

    expect(result.current.analysisCondition.minEvaluationYears).toBe(
      condition.default,
    );

    const another = condition.choices.find(
      (choice) => choice.value !== condition.default,
    )!;
    act(() =>
      result.current.setAnalysisCondition({
        minEvaluationYears: Number(another.value),
      }),
    );
    expect(result.current.analysisCondition.minEvaluationYears).toBe(
      Number(another.value),
    );

    act(() => result.current.setAnalysisCondition({ minEvaluationYears: 7 }));
    expect(result.current.analysisCondition.minEvaluationYears).toBe(
      Number(another.value),
    );
  });

  it("등록표 후보에 없는 최소 평가 기간 보관값은 기본값으로 시작하고 그 사실을 알린다", () => {
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: { minEvaluationYears: 99, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.analysisCondition.minEvaluationYears).toBe(
      registryFixture.analysisConditions.minEvaluationYears.default,
    );
    expect(
      result.current.invalidValues.some(
        (entry) => entry.reason === "CONDITION_NOT_IN_CHOICES",
      ),
    ).toBe(true);
  });

  it("보관된 시작 금액이 등록표 범위 밖이면 비우고 복원을 기다린다", () => {
    // S15P21E205-274, SC-002·FR-017. 축 값과 같은 잣대로 다시 판정한다.
    const range = registryFixture.analysisConditions.initialAmount;
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        initialAmount: range.max + range.step,
      },
      invalidValues: [],
    });

    const { result } = renderSettings();

    // 범위 밖 값이 상태에도 저장소에도 남지 않는다.
    expect(result.current.analysisCondition.initialAmount).toBeUndefined();
    expect(
      (readStored().analysisCondition as Record<string, unknown>).initialAmount,
    ).toBeUndefined();

    const entry = result.current.invalidValues.find(
      (item) => item.axisName === "initialAmount",
    );
    expect(entry).toMatchObject({
      strategyId: null,
      reason: "OUT_OF_RANGE",
      restorable: true,
    });
  });

  it("간격에 맞지 않는 보관 금액도 비운다", () => {
    const range = registryFixture.analysisConditions.initialAmount;
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        initialAmount: range.min + 1,
      },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.analysisCondition.initialAmount).toBeUndefined();
    expect(
      result.current.invalidValues.find(
        (item) => item.axisName === "initialAmount",
      )?.reason,
    ).toBe("OFF_STEP");
  });

  it("비워 둔 시작 금액의 안내는 새로 고쳐도 복원 전까지 남는다", () => {
    // 옮기지 않으면 새로 고치는 순간 안내가 사라지고 기본값이 조용히 쓰인다.
    const range = registryFixture.analysisConditions.initialAmount;
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        initialAmount: range.max + range.step,
      },
      invalidValues: [],
    });

    renderSettings().unmount();
    const { result } = renderSettings();

    expect(
      result.current.invalidValues.some(
        (item) => item.axisName === "initialAmount",
      ),
    ).toBe(true);
  });

  it("시작 금액 복원은 안내를 지우고 기본값을 쓰게 한다", () => {
    const range = registryFixture.analysisConditions.initialAmount;
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        initialAmount: range.max + range.step,
      },
      invalidValues: [],
    });

    const { result } = renderSettings();
    act(() => result.current.resetAnalysisCondition("initialAmount"));

    expect(
      result.current.invalidValues.some(
        (item) => item.axisName === "initialAmount",
      ),
    ).toBe(false);
    expect(result.current.analysisCondition.initialAmount).toBeUndefined();
  });

  it("범위 밖 시작 금액은 입력으로도 반영하지 않는다", () => {
    // 복원 경로와 입력 경로가 다른 잣대를 쓰면 한쪽으로 범위 밖 값이 들어온다(SC-002).
    const range = registryFixture.analysisConditions.initialAmount;
    const { result } = renderSettings();

    act(() =>
      result.current.setAnalysisCondition({
        initialAmount: range.min - range.step,
      }),
    );
    expect(result.current.analysisCondition.initialAmount).toBeUndefined();

    act(() =>
      result.current.setAnalysisCondition({ initialAmount: range.min }),
    );
    expect(result.current.analysisCondition.initialAmount).toBe(range.min);
  });

  it("분석 기간은 처음에 비어 있고 두 날짜가 맞을 때만 함께 반영된다", () => {
    // T067. 비어 있으면 화면이 평가 가능 범위 전체를 쓴다. 범위를 상태에 복사해 두지 않는다.
    const { result } = renderSettings();
    expect(result.current.analysisCondition.periodStart).toBeUndefined();
    expect(result.current.analysisCondition.periodEnd).toBeUndefined();

    // 한쪽만 있으면 기간이 아니다.
    act(() =>
      result.current.setAnalysisCondition({ periodStart: "2021-01-04" }),
    );
    expect(result.current.analysisCondition.periodStart).toBeUndefined();

    act(() =>
      result.current.setAnalysisCondition({
        periodStart: "2021-01-04",
        periodEnd: "2023-12-28",
      }),
    );
    expect(result.current.analysisCondition).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: "2023-12-28",
    });

    // 시작이 종료보다 늦어지거나 날짜가 아니면 앞의 기간을 그대로 둔다.
    act(() =>
      result.current.setAnalysisCondition({ periodStart: "2024-01-02" }),
    );
    act(() => result.current.setAnalysisCondition({ periodEnd: "2023-02-30" }));
    expect(result.current.analysisCondition).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: "2023-12-28",
    });
    expect(readStored().analysisCondition).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: "2023-12-28",
    });

    act(() => result.current.resetAnalysisCondition("period"));
    expect(result.current.analysisCondition.periodStart).toBeUndefined();
    expect(result.current.analysisCondition.periodEnd).toBeUndefined();
  });

  it("읽을 수 없는 보관 기간은 지우고 그 사실을 알린다", () => {
    store({
      registryVersion: registryFixture.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        periodStart: "2023-06-01",
        periodEnd: "2022-06-01",
      },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.analysisCondition.periodStart).toBeUndefined();
    expect(result.current.analysisCondition.periodEnd).toBeUndefined();
    const notice = result.current.invalidValues.find(
      (item) => item.axisName === "period",
    );
    expect(notice).toMatchObject({
      strategyId: null,
      reason: "PERIOD_UNREADABLE",
      restorable: false,
    });

    // 새 기간을 고르면 "지웠다"는 안내는 지금 기간과 어긋나므로 사라진다.
    act(() =>
      result.current.setAnalysisCondition({
        periodStart: "2021-01-04",
        periodEnd: "2023-12-28",
      }),
    );
    expect(
      result.current.invalidValues.some((item) => item.axisName === "period"),
    ).toBe(false);
  });

  it("읽을 수 있는 보관 기간은 등록표를 다시 받아도 그대로 남는다", () => {
    store({
      registryVersion: "OLD-VERSION",
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: null,
      valuesByStrategy: {},
      analysisCondition: {
        minEvaluationYears: 1,
        scenario: "FULL_PERIOD",
        periodStart: "2021-01-04",
        periodEnd: "2023-12-28",
      },
      invalidValues: [],
    });

    const { result } = renderSettings();

    expect(result.current.analysisCondition).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: "2023-12-28",
    });
    expect(result.current.invalidValues).toHaveLength(0);
  });

  it("선택 전략의 워밍업을 현재 설정으로 계산한다", () => {
    const { result } = renderSettings();
    act(() => result.current.select(unselectableStrategy.strategyId));

    // 픽스처의 워밍업 규칙은 축 둘을 더한다. 기본값 합과 같아야 한다.
    const [first, second] = unselectableStrategy.warmupRule!.sum as string[];
    const expected =
      Number(
        unselectableStrategy.axes.find((axis) => axis.name === first)!
          .defaultValue,
      ) +
      Number(
        unselectableStrategy.axes.find((axis) => axis.name === second)!
          .defaultValue,
      );
    expect(result.current.warmupTradingDays).toBe(expected);

    const skipAxis = unselectableStrategy.axes.find(
      (axis) => axis.name === second,
    )!;
    const other = skipAxis.choices!.find(
      (choice) => choice.value !== skipAxis.defaultValue,
    )!;
    act(() => {
      result.current.setValue(
        unselectableStrategy.strategyId,
        skipAxis.name,
        other.value,
      );
    });

    expect(result.current.warmupTradingDays).not.toBe(expected);
  });

  it("워밍업 규칙이 없는 전략은 0이다", () => {
    const plain = registryFixture.strategies.find(
      (strategy) => strategy.warmupRule === undefined,
    )!;
    const { result } = renderSettings();
    act(() => result.current.select(plain.strategyId));
    expect(result.current.warmupTradingDays).toBe(0);
  });

  it("등록표를 받기 전에는 준비되지 않은 상태다", () => {
    const { result, rerender } = renderSettings(null, null);

    expect(result.current.ready).toBe(false);
    expect(result.current.selectedStrategyId).toBeNull();

    rerender({ registry: registryFixture, universeSize: UNIVERSE_SIZE });
    expect(result.current.ready).toBe(true);
  });

  it("sessionStorage 접근이 예외를 던져도 훅이 동작한다", () => {
    // 사생활 보호 모드 등에서 읽기·쓰기가 모두 막힐 수 있다(research R8).
    const blocked = () => {
      throw new Error("storage blocked");
    };
    vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(blocked);
    vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(blocked);

    const { result } = renderSettings();
    act(() => result.current.select(selectableStrategy.strategyId));

    expect(result.current.ready).toBe(true);
    expect(result.current.values?.[choiceAxis.name]).toEqual(
      choiceAxis.defaultValue,
    );
  });

  it("보관된 값의 모양이 어긋나면 통째로 버리고 기본 상태로 시작한다", () => {
    window.sessionStorage.setItem(STRATEGY_SETTINGS_STORAGE_KEY, "{ not json");

    const { result } = renderSettings();

    expect(result.current.ready).toBe(true);
    expect(result.current.selectedStrategyId).toBeNull();
    expect(result.current.invalidValues).toHaveLength(0);
  });
});

/**
 * 조건부 축(FR-003, 계약 §1 `appliesWhen`). 결정 축 `gate`가 `LEFT`면 `leftGap`만, `RIGHT`면 `rightPick`만
 * 뜻이 있다. 훅은 축 이름을 모르고 조건만 본다.
 */
describe("useStrategySettings — 조건부 축", () => {
  const registry = withConditionalStrategy();
  const id = conditionalStrategy.strategyId;
  const gate = conditionalStrategy.axes[0];
  const leftGap = conditionalStrategy.axes[1];
  const rightPick = conditionalStrategy.axes[2];

  afterEach(() => {
    window.sessionStorage.clear();
  });

  it("처음 고르면 조건이 참인 축만 기본값으로 채우고 나머지는 만들지 않는다", () => {
    const { result } = renderSettings(registry);
    act(() => result.current.select(id));

    expect(result.current.values).toEqual({
      [gate.name]: gate.defaultValue,
      [leftGap.name]: leftGap.defaultValue,
    });
    expect(result.current.isComplete(id)).toBe(true);
    // 보관값에도 조건 밖 축이 없다.
    const stored = readStored() as {
      valuesByStrategy: Record<string, Record<string, unknown>>;
    };
    expect(stored.valuesByStrategy[id]).not.toHaveProperty(rightPick.name);
  });

  it("결정 축을 바꾸면 밀려난 축의 값은 지우고 새로 열린 축은 기본값에서 시작한다", () => {
    const { result } = renderSettings(registry);
    act(() => result.current.select(id));
    act(() => {
      result.current.setValue(id, leftGap.name, 5);
    });
    expect(result.current.values?.[leftGap.name]).toBe(5);

    act(() => {
      result.current.setValue(id, gate.name, "RIGHT");
    });

    expect(result.current.values).toEqual({
      [gate.name]: "RIGHT",
      [rightPick.name]: rightPick.defaultValue,
    });
    expect(result.current.isComplete(id)).toBe(true);
    // 바꾼 값(5)은 조건 밖으로 밀려나 남지 않는다. 다시 돌아오면 기본값에서 시작한다.
    act(() => {
      result.current.setValue(id, gate.name, "LEFT");
    });
    expect(result.current.values?.[leftGap.name]).toBe(leftGap.defaultValue);
    expect(result.current.values).not.toHaveProperty(rightPick.name);
  });

  it("보관값의 조건 밖 축은 무효 안내 없이 지우고, 조건이 참인데 없는 축은 기본값으로 채운다", () => {
    store({
      registryVersion: registry.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: id,
      // gate가 RIGHT인데 leftGap이 남아 있고(범위 밖 값이라도) rightPick이 없다.
      valuesByStrategy: { [id]: { [gate.name]: "RIGHT", [leftGap.name]: 999 } },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings(registry);

    expect(result.current.values).toEqual({
      [gate.name]: "RIGHT",
      [rightPick.name]: rightPick.defaultValue,
    });
    // 그리지도 싣지도 않는 축을 고치라고 하지 않는다.
    expect(result.current.invalidValues).toHaveLength(0);
    expect(result.current.isComplete(id)).toBe(true);
  });

  it("조건이 참인 축의 보관값이 무효면 비워 두고 안내한다 — 조건이 안내를 삼키지 않는다", () => {
    store({
      registryVersion: registry.registryVersion,
      universeSize: UNIVERSE_SIZE,
      selectedStrategyId: id,
      valuesByStrategy: { [id]: { [gate.name]: "LEFT", [leftGap.name]: 999 } },
      analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
      invalidValues: [],
    });

    const { result } = renderSettings(registry);

    expect(result.current.values).toEqual({ [gate.name]: "LEFT" });
    expect(result.current.invalidValues).toEqual([
      expect.objectContaining({
        strategyId: id,
        axisName: leftGap.name,
        restorable: true,
      }),
    ]);
    expect(result.current.isComplete(id)).toBe(false);
  });
});
