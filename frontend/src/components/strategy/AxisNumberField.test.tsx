import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AxisNumberField } from "./AxisNumberField";

/**
 * 입력형 항목은 범위 밖 값을 저장하지 않고 잘라 넣지도 않는다(FR-006, quickstart 5).
 *
 * <p>라벨·범위는 합성 값이다. 실제 등록표의 비중 상한을 옮겨 적지 않는다.
 */

const range = { min: 10, max: 100, step: 5 };

function renderField(initial: number | undefined) {
  const onChange = vi.fn();

  // 실제 상태처럼 받은 값을 되돌려 주는 틀. 입력칸이 바깥 값과 맞춰지는 경로까지 함께 본다.
  function Harness() {
    const [value, setValue] = useState<number | undefined>(initial);
    return (
      <>
        <AxisNumberField
          label="합성 상한"
          unit="퍼센트"
          range={range}
          defaultValue={20}
          value={value}
          onChange={(next) => {
            onChange(next);
            setValue(next);
          }}
        />
        <output data-testid="stored">
          {value === undefined ? "비어 있음" : String(value)}
        </output>
      </>
    );
  }

  render(<Harness />);
  return {
    onChange,
    input: screen.getByRole("textbox", { name: /합성 상한/ }),
  };
}

describe("AxisNumberField", () => {
  it("범위 밖 값은 허용 범위를 안내하고 반영하지 않으며 최솟값으로 잘라 넣지 않는다", async () => {
    const user = userEvent.setup();
    const { onChange, input } = renderField(20);

    await user.clear(input);
    await user.type(input, "5");

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("stored").textContent).toContain("20");
    expect((input as HTMLInputElement).value).toBe("5");
    expect(input.getAttribute("aria-invalid")).toBe("true");
    expect(screen.getByText(/허용 범위 밖입니다/).textContent).toContain(
      "허용 범위는 10~100퍼센트",
    );
    expect(screen.getByText(/저장하지 않았습니다/)).not.toBeNull();
  });

  it("범위 안이고 간격에 맞는 값은 반영한다", async () => {
    const user = userEvent.setup();
    const { onChange, input } = renderField(20);

    await user.clear(input);
    await user.type(input, "35");

    expect(onChange).toHaveBeenLastCalledWith(35);
    expect(screen.getByTestId("stored").textContent).toContain("35");
    expect(input.getAttribute("aria-invalid")).toBe("false");
  });

  it("치는 도중의 범위 안 글자가 먼저 반영돼도 마지막 글이 범위 밖이면 입력 전 값으로 돌아간다", async () => {
    const user = userEvent.setup();
    const { input } = renderField(20);

    // `10`은 범위 안이라 한 번 반영되지만 `105`는 범위 밖이다.
    await user.clear(input);
    await user.type(input, "105");

    expect(screen.getByTestId("stored").textContent).toBe("20");
    expect((input as HTMLInputElement).value).toBe("105");
    expect(screen.getByText(/허용 범위 밖입니다/)).not.toBeNull();
  });

  it("간격에 맞지 않는 값은 간격을 안내하고 반영하지 않는다", async () => {
    const user = userEvent.setup();
    const { onChange, input } = renderField(20);

    await user.clear(input);
    await user.type(input, "33");

    expect(onChange).not.toHaveBeenCalledWith(33);
    expect(screen.getByTestId("stored").textContent).toContain("20");
    expect(
      screen.getByText(/등록된 간격에 맞지 않습니다/).textContent,
    ).toContain("5퍼센트 간격");
  });

  it("숫자가 아닌 글은 반영하지 않는다", async () => {
    const user = userEvent.setup();
    const { onChange, input } = renderField(20);

    await user.clear(input);
    await user.type(input, "abc");

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByText(/숫자로 입력해 주세요/)).not.toBeNull();
  });

  it("되돌리는 단추를 두지 않는다 — 설정 행 이름 옆 아이콘이 맡는다", () => {
    renderField(35);

    expect(screen.queryByRole("button", { name: /기본값 복원/ })).toBeNull();
  });

  it("재검증이 비워 둔 항목은 빈칸으로 두고 값을 넣으라고 안내한다", () => {
    const { input } = renderField(undefined);

    expect((input as HTMLInputElement).value).toBe("");
    expect(screen.getByText(/값을 입력해 주세요/)).not.toBeNull();
  });
});
