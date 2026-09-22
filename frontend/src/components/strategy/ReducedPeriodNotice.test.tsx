import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { ReducedPeriodNotice } from "./ReducedPeriodNotice";
import type { ReducedPeriodInput } from "./reducedPeriod";

const input: ReducedPeriodInput = {
  window: { start: "2019-06-03", end: "2024-12-30" },
  reduction: {
    startWithoutWarmup: "2019-01-02",
    bindingCandidate: "091160.XKRX",
  },
  bindingName: "가섹터",
  warmupTradingDays: 81,
  causes: [
    { label: "흐름 창", value: 60, unit: "거래일" },
    { label: "쉬는 날", value: 21, unit: "거래일" },
  ],
};

/** 003 T066. 사실은 늘 보이고 까닭은 펼쳐 본다(SC-004, S15P21E205-406). */
describe("ReducedPeriodNotice", () => {
  it("평가 기간만 한 줄로 보이고 까닭은 처음에 보이지 않는다", () => {
    render(<ReducedPeriodNotice input={input} />);

    const notice = screen.getByRole("status", { name: "평가 기간" });
    expect(notice.textContent).toContain("2019-06-03 ~ 2024-12-30");
    // 고른 적 없는 기간을 두고 "줄었다"고 말하지 않는다.
    expect(notice.textContent).not.toContain("줄었");
    expect(screen.queryByText(/가섹터/)).toBeNull();
  });

  it("도움말에 마우스를 얹으면 자료 시작일과 워밍업을 이루는 항목이 보인다", async () => {
    const user = userEvent.setup();
    render(<ReducedPeriodNotice input={input} />);

    await user.hover(screen.getByRole("button", { name: "평가 기간 도움말" }));

    const cause = (await screen.findByText(/가섹터/)).textContent ?? "";
    expect(cause).toContain("2019-01-02");
    expect(cause).toContain("흐름 창 60거래일·쉬는 날 21거래일");
    expect(cause).toContain("81거래일");
    expect(cause).toContain("2019-06-03");
  });

  it("워밍업 항목 이름이 없으면 워밍업이라고만 적고 이름을 지어내지 않는다", async () => {
    const user = userEvent.setup();
    render(<ReducedPeriodNotice input={{ ...input, causes: [] }} />);

    await user.hover(screen.getByRole("button", { name: "평가 기간 도움말" }));

    const cause = (await screen.findByText(/가섹터/)).textContent ?? "";
    expect(cause).toContain("워밍업 81거래일");
    expect(cause).not.toContain("지금 설정의");
  });

  it("까닭을 받지 못한 판정은 판정 문장만 보이고 도움말을 두지 않는다", () => {
    render(
      <ReducedPeriodNotice
        input={null}
        fallbackMessage="평가 기간이 줄어듭니다."
      />,
    );

    expect(screen.getByRole("status", { name: "평가 기간" }).textContent).toBe(
      "평가 기간이 줄어듭니다.",
    );
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("줄지 않았으면 아무것도 그리지 않는다", () => {
    const { container } = render(<ReducedPeriodNotice input={null} />);

    expect(container.childElementCount).toBe(0);
    expect(screen.queryByRole("status")).toBeNull();
  });
});
