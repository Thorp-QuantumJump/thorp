import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import {
  TargetWeightEditor,
  type TargetWeightRow,
} from "./TargetWeightEditor";

/**
 * 자산 배분 영역이다. 무엇을 담을지는 앞 단계가 정했고 여기서는 얼마를 줄지만 정한다.
 *
 * <p>합계를 자동으로 맞추지 않는다는 것이 이 영역의 핵심 규칙이다(SC-002). 한 값을 고쳤다고
 * 나머지가 저절로 움직이면 사용자가 방금 정한 값이 말없이 달라진다.
 */

const rows: TargetWeightRow[] = [
  { sectorId: "SEMICONDUCTOR", name: "반도체", weightTenths: 600 },
  { sectorId: "AUTO", name: "자동차", weightTenths: 400 },
];

function renderEditor(
  overrides: Partial<{
    rows: readonly TargetWeightRow[] | null;
    onChange: (sectorId: string, weightTenths: number) => void;
    onDistributeEven: () => void;
  }> = {},
) {
  const onChange = overrides.onChange ?? vi.fn();
  const onDistributeEven = overrides.onDistributeEven ?? vi.fn();

  const view = render(
    <MemoryRouter>
      <TargetWeightEditor
        rows={overrides.rows === undefined ? rows : overrides.rows}
        onChange={onChange}
        onDistributeEven={onDistributeEven}
      />
    </MemoryRouter>,
  );

  return { onChange, onDistributeEven, unmount: view.unmount };
}

function region(): HTMLElement {
  return screen.getByRole("region", { name: "자산 배분" });
}

function totalText(): string {
  return (
    screen
      .getAllByRole("status")
      .find((node) => node.textContent?.includes("합계"))?.textContent ?? ""
  );
}

describe("자산 배분 영역", () => {
  it("고른 대상마다 값을 정하는 수단을 둘 준다", () => {
    renderEditor();

    // 슬라이더는 대충 맞추는 자리, 숫자 칸은 정확히 넣는 자리다. 가리키는 값은 하나다.
    expect(within(region()).getAllByRole("slider")).toHaveLength(2);
    expect(within(region()).getAllByRole("spinbutton")).toHaveLength(2);
    expect(
      (
        within(region()).getByRole("spinbutton", {
          name: "반도체 비중",
        }) as HTMLInputElement
      ).value,
    ).toBe("60.0");
  });

  it("한 값을 고쳐도 나머지를 건드리지 않는다 (SC-002)", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderEditor({ onChange });

    const field = within(region()).getByRole("spinbutton", {
      name: "반도체 비중",
    });
    await user.clear(field);
    await user.type(field, "40");

    // 올라오는 것은 방금 고친 대상 하나뿐이다. 나머지를 비율대로 줄이는 계산을 하지 않는다.
    expect(onChange).toHaveBeenLastCalledWith("SEMICONDUCTOR", 400);
    expect(
      onChange.mock.calls.every(([sectorId]) => sectorId === "SEMICONDUCTOR"),
    ).toBe(true);
  });

  it("합계와 모자란 몫을 그 자리에서 알린다 (FR-007)", () => {
    renderEditor({
      rows: [
        { sectorId: "SEMICONDUCTOR", name: "반도체", weightTenths: 400 },
        { sectorId: "AUTO", name: "자동차", weightTenths: 400 },
      ],
    });

    expect(totalText()).toContain("합계 80.0%");
    expect(totalText()).toContain("20.0%p 부족");
  });

  it("넘친 몫도 같은 자리에서 알린다", () => {
    renderEditor({
      rows: [
        { sectorId: "SEMICONDUCTOR", name: "반도체", weightTenths: 700 },
        { sectorId: "AUTO", name: "자동차", weightTenths: 400 },
      ],
    });

    expect(totalText()).toContain("10.0%p 초과");
  });

  it("동일하게 나누기는 누를 때만 값을 바꾼다", async () => {
    const user = userEvent.setup();
    const { onDistributeEven } = renderEditor();

    // 합계가 맞는 동안에도 자리를 지킨다. 값을 고치는 중에 단추가 나타났다 사라지면 초점이 흔들린다.
    const button = within(region()).getByRole("button", {
      name: "동일하게 나누기",
    });
    expect(onDistributeEven).not.toHaveBeenCalled();

    await user.click(button);
    expect(onDistributeEven).toHaveBeenCalledTimes(1);
  });

  it("0%인 대상이 있을 때만 그 뜻을 알린다 (FR-016)", () => {
    const { unmount } = renderEditor();
    expect(screen.queryByText(/후보 집합에는/)).toBeNull();
    unmount();

    renderEditor({
      rows: [
        { sectorId: "SEMICONDUCTOR", name: "반도체", weightTenths: 1000 },
        { sectorId: "AUTO", name: "자동차", weightTenths: 0 },
      ],
    });

    // 0%인 대상을 선택에서 빼지 않는다. 자동 배분 전략은 그 대상을 여전히 후보로 삼는다(SC-010).
    expect(screen.getByText(/그대로 남습니다/)).toBeTruthy();
  });

  it("앞 단계 선택을 읽지 못했으면 빈 표 대신 그 사실을 알린다", () => {
    renderEditor({ rows: null });

    expect(screen.getByText(/읽지 못했습니다/)).toBeTruthy();
    expect(within(region()).queryByRole("spinbutton")).toBeNull();
  });

  it("담을 대상을 바꾸는 길은 앞 단계로 이어진다", () => {
    renderEditor();

    const link = screen.getByRole("link", {
      name: "투자 대상 고르기로 돌아가세요",
    });
    expect(link.getAttribute("href")).toBe("/diagnosis/sectors");
  });
});
