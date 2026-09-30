import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import {
  selectableStrategy,
  unselectableStrategy,
} from "@/test/strategyRegistryFixtures";
import type { AxisValue, AxisView } from "@/types/strategyRegistry";
import { AxisChoiceField } from "./AxisChoiceField";

/**
 * 목록형 항목은 등록된 후보만 보인다(FR-006, 계약 §1 `choices`).
 *
 * <p>픽스처의 축은 등록표에 없는 이름이다. 화면이 축을 알고 있어도 검사가 통과하지 않게 한다.
 */

function axisNamed(strategyAxes: AxisView[], name: string): AxisView {
  const axis = strategyAxes.find((candidate) => candidate.name === name);
  if (!axis) throw new Error(`픽스처에 축이 없다: ${name}`);
  return axis;
}

const pickCount = axisNamed(unselectableStrategy.axes, "pickCount");
const resetTiming = axisNamed(selectableStrategy.axes, "resetTiming");

/** 값을 주지 않으면 기본값으로 그린다. `undefined`를 명시하면 비워 둔 축이다. */
function renderField(axis: AxisView, ...given: [] | [AxisValue | undefined]) {
  const value = given.length === 0 ? axis.defaultValue : given[0];
  const onChange = vi.fn();
  render(<AxisChoiceField axis={axis} value={value} onChange={onChange} />);
  return { onChange };
}

describe("AxisChoiceField", () => {
  it("등록된 후보만 그리고 자유 입력칸을 두지 않는다", () => {
    renderField(pickCount);

    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(pickCount.choices?.length ?? -1);
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("spinbutton")).toBeNull();
  });

  it("고를 수 없는 후보는 목록에 남기되 고를 수 없다", async () => {
    const user = userEvent.setup();
    const { onChange } = renderField(pickCount, 2);

    const blocked = screen.getByRole("radio", {
      name: (name) => name.startsWith("4"),
    });
    expect((blocked as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/지금 조건에서 고를 수 없음/)).not.toBeNull();

    await user.click(blocked);
    expect(onChange).not.toHaveBeenCalled();
    expect((blocked as HTMLInputElement).checked).toBe(false);
  });

  it("합성 값은 등록표의 문구로 보이고 고르면 원래 객체를 그대로 넘긴다", async () => {
    const user = userEvent.setup();
    const { onChange } = renderField(resetTiming);

    // 기본값이 같은 모양의 다른 객체여도 선택된 것으로 보인다(칸마다 견준다).
    expect(
      (screen.getByRole("radio", { name: "달 끝마다" }) as HTMLInputElement)
        .checked,
    ).toBe(true);

    await user.click(screen.getByRole("radio", { name: "30거래일마다" }));

    const fixed = resetTiming.choices?.[1];
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toBe(fixed?.value);
    // 칸 이름이 화면 글에 새어 나오지 않는다.
    expect(document.body.textContent).not.toContain("FIXED_DAYS");
  });

  it("비워 둔 축이면 아무 후보도 고른 상태가 아니다", () => {
    renderField(pickCount, undefined);
    for (const radio of screen.getAllByRole("radio")) {
      expect((radio as HTMLInputElement).checked).toBe(false);
    }
  });

  it("후보만 그린다: 이름·설명·되돌리기는 설정 행이 맡아 여기 두지 않는다", () => {
    const drift = axisNamed(selectableStrategy.axes, "driftWindow");
    renderField(drift, 15);

    // 묶음 이름은 행 이름과 같다. 보이는 제목은 행 머리가 가진다.
    expect(screen.getByRole("group", { name: drift.label })).not.toBeNull();
    // 설명은 행 이름 옆 도움말 안에 있다(`S15P21E205-406`). 후보 자리에는 후보만 남는다.
    expect(screen.queryByText(drift.description ?? "")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
