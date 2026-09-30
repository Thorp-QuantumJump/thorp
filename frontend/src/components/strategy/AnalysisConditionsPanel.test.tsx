import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  STRATEGY_SETTINGS_STORAGE_KEY,
  useStrategySettings,
} from "@/hooks/useStrategySettings";
import { registryFixture } from "@/test/strategyRegistryFixtures";
import {
  AnalysisConditionsPanel,
  COST_LOADING,
  COST_UNAVAILABLE,
  type AnalysisConditionPatch,
  type CostAssumptionView,
} from "./AnalysisConditionsPanel";

/**
 * 분석 조건 패널(T065)을 실제 설정 훅과 함께 돌린다(FR-009·FR-010·FR-011a, SC-002).
 *
 * <p><b>"저장되지 않는다"를 훅 상태와 `sessionStorage` 둘 다로 본다.</b> 패널이 부르지 않았다는 것만 보면,
 * 훅이 다른 경로로 받아들이는 결함을 놓친다. 금액 범위와 후보는 합성 등록표의 값이다.
 */

const range = { start: "2020-01-02", end: "2024-12-30" };
const amount = registryFixture.analysisConditions.initialAmount;

const cost: CostAssumptionView = {
  version: "SYN-COST-1",
  provisional: true,
  rates: { commissionBps: 1.5, spreadBps: 5, slippageBps: 2.25, taxBps: 20 },
};

function Harness({
  onChange,
  costView = cost,
}: {
  onChange: (patch: AnalysisConditionPatch) => void;
  costView?: CostAssumptionView | "loading" | null;
}) {
  const settings = useStrategySettings(registryFixture, 3);
  if (!settings.ready) return <p>준비 중</p>;
  const condition = settings.analysisCondition;
  const chosen =
    condition.periodStart !== undefined && condition.periodEnd !== undefined
      ? { start: condition.periodStart, end: condition.periodEnd }
      : null;
  return (
    <>
      <AnalysisConditionsPanel
        conditions={registryFixture.analysisConditions}
        range={range}
        period={chosen ?? range}
        periodChosen={chosen !== null}
        sectorCount={3}
        initialAmount={condition.initialAmount ?? amount.default}
        minEvaluationYears={condition.minEvaluationYears}
        targetVolatility={condition.targetVolatility}
        cost={costView}
        onChange={(patch) => {
          onChange(patch);
          settings.setAnalysisCondition(patch);
        }}
        onReset={settings.resetAnalysisCondition}
      />
      <output data-testid="condition">{JSON.stringify(condition)}</output>
    </>
  );
}

function storedCondition(): Record<string, unknown> {
  const raw = window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY);
  return raw === null
    ? {}
    : (JSON.parse(raw).analysisCondition as Record<string, unknown>);
}

function stateCondition(): Record<string, unknown> {
  return JSON.parse(
    screen.getByTestId("condition").textContent ?? "{}",
  ) as Record<string, unknown>;
}

/** 영역 안의 접힌 설정 행을 모두 펼친다. 행마다 여닫으므로 폼을 보려면 그 행을 열어야 한다. */
/**
 * 설정 행의 펼침 단추. 접근 가능 이름이 '항목 이름 + 지금 값'이라 이름 전체로는 찾을 수 없으므로, 이름이 가리키는
 * 첫 조각(항목 이름)으로 찾는다.
 */
function rowToggle(
  label: string,
  container: ParentNode = document,
): HTMLButtonElement | null {
  return (
    Array.from(
      container.querySelectorAll<HTMLButtonElement>('[data-slot="setting-toggle"]'),
    ).find((button) => {
      const [labelId] = (button.getAttribute("aria-labelledby") ?? "").split(" ");
      return document.getElementById(labelId)?.textContent === label;
    }) ?? null
  );
}

async function openAllRows(
  user: ReturnType<typeof userEvent.setup>,
  region: HTMLElement,
) {
  for (const toggle of Array.from(
    region.querySelectorAll<HTMLButtonElement>(
      '[data-slot="setting-toggle"][aria-expanded="false"]',
    ),
  )) {
    await user.click(toggle);
  }
}

async function renderOpen(costView?: CostAssumptionView | "loading" | null) {
  const onChange = vi.fn<(patch: AnalysisConditionPatch) => void>();
  const user = userEvent.setup();
  render(<Harness onChange={onChange} costView={costView} />);
  const panel = await screen.findByRole("region", { name: "분석 조건" });
  await openAllRows(user, panel);
  return { onChange, user, panel };
}

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  window.sessionStorage.clear();
});

describe("AnalysisConditionsPanel", () => {
  it("범위 안내가 따라야 하는 기간·금액만 펼치고, 짧은 선택은 바로 두며, 값은 늘 보인다", async () => {
    render(<Harness onChange={vi.fn()} />);

    const panel = await screen.findByRole("region", { name: "분석 조건" });
    const toggles = Array.from(
      panel.querySelectorAll('[data-slot="setting-toggle"]'),
    );
    // 펼치는 행은 기간과 금액 둘이다. 최소 평가 기간·변동성 제한은 바로 고르고, 나머지는 값만 있다.
    expect(toggles).toEqual([rowToggle("분석 기간"), rowToggle("시작 금액")]);
    for (const toggle of toggles) {
      expect(toggle.getAttribute("aria-expanded")).toBe("false");
      // 접힌 동안에는 없는 요소를 가리키지 않는다.
      expect(toggle.getAttribute("aria-controls")).toBeNull();
    }
    // 날짜·금액 칸은 펼치기 전에는 없다. 날짜 칸은 textbox 역할이 아니라서 요소로 함께 본다.
    expect(
      document.querySelectorAll('input:not([type="radio"]), select, textarea'),
    ).toHaveLength(0);
    expect(
      within(panel).getByRole("group", { name: "최소 평가 기간" }),
    ).toBeTruthy();
    expect(within(panel).getByRole("group", { name: "변동성 제한" })).toBeTruthy();
    // 값은 접혀 있어도 늘 보인다(FR-002).
    const summary = panel.textContent ?? "";
    expect(summary).toContain(`${range.start} ~ ${range.end}`);
    expect(summary).toContain(amount.default.toLocaleString("ko-KR"));
    expect(summary).toContain("섹터 3개");
    expect(
      (within(panel).getByRole("radio", { name: "쓰지 않음" }) as HTMLInputElement).checked,
    ).toBe(true);
    expect(summary).toContain("SYN-COST-1");
  });

  it("펼침 단추의 이름에 지금 값이 들어가 펼치지 않아도 값이 읽힌다", async () => {
    render(<Harness onChange={vi.fn()} />);
    await screen.findByRole("region", { name: "분석 조건" });

    // 보이는 글자(값)가 이름에 들어가야 음성으로 부르는 사용자가 찾고, 화면 읽기 사용자가 값을 듣는다(WCAG 2.5.3).
    expect(
      screen.getByRole("button", {
        name: `시작 금액 ${amount.default.toLocaleString("ko-KR")}${amount.unit ?? ""}`,
      }),
    ).toBe(rowToggle("시작 금액"));
    expect(
      screen.getByRole("button", { name: `분석 기간 ${range.start} ~ ${range.end}` }),
    ).toBe(rowToggle("분석 기간"));
  });

  it("되돌리기는 바꾼 행 이름 옆에만 나타나고, 누르면 기본값으로 돌아가며 초점이 그 행에 남는다", async () => {
    const user = userEvent.setup();
    render(<Harness onChange={vi.fn()} />);
    const panel = await screen.findByRole("region", { name: "분석 조건" });

    // 바꾼 것이 없으면 되돌리기가 하나도 없다.
    expect(within(panel).queryByRole("button", { name: /기본값 복원|범위 전체로/ })).toBeNull();

    // 짧은 선택: 최소 평가 기간
    await user.click(within(panel).getByRole("radio", { name: "엄격(3년)" }));
    const yearsReset = within(panel).getByRole("button", { name: "최소 평가 기간 기본값 복원" });
    await user.click(yearsReset);
    expect(stateCondition().minEvaluationYears).toBe(1);
    expect(within(panel).queryByRole("button", { name: "최소 평가 기간 기본값 복원" })).toBeNull();
    // 사라진 아이콘 대신 그 행의 고른 후보가 초점을 받는다. 문서 맨 앞으로 떨어지지 않는다.
    expect(document.activeElement).toBe(within(panel).getByRole("radio", { name: "기본(1년)" }));

    // 펼치는 행: 시작 금액
    await user.click(rowToggle("시작 금액")!);
    fireEvent.change(within(panel).getByRole("textbox", { name: /시작 금액/ }), {
      target: { value: String(amount.default + amount.step) },
    });
    await user.click(within(panel).getByRole("button", { name: "시작 금액 기본값 복원" }));
    expect(stateCondition().initialAmount).toBeUndefined();
    expect(
      (within(panel).getByRole("textbox", { name: /시작 금액/ }) as HTMLInputElement).value,
    ).toBe(amount.default.toLocaleString("ko-KR"));
    expect(document.activeElement).toBe(rowToggle("시작 금액"));

    // 켜고 끄는 선택: 변동성 제한
    await user.click(within(panel).getByRole("radio", { name: "씀" }));
    await user.click(within(panel).getByRole("button", { name: "변동성 제한 기본값 복원" }));
    expect(stateCondition().targetVolatility).toBeUndefined();
    expect(document.activeElement).toBe(within(panel).getByRole("radio", { name: "쓰지 않음" }));
  });

  it("범위 전체와 같은 날짜를 골라 둔 채 입력을 망가뜨려도, 되돌리면 입력칸이 범위 전체로 다시 맞춰진다", async () => {
    const { user } = await renderOpen();
    const start = () => screen.getByLabelText("시작일") as HTMLInputElement;

    // 고른 기간이 범위 전체와 같아지게 한다. 사용자가 고른 기간이라 되돌리기가 있다.
    fireEvent.focus(start());
    fireEvent.change(start(), { target: { value: "2021-01-04" } });
    fireEvent.change(start(), { target: { value: range.start } });
    expect(stateCondition()).toMatchObject({ periodStart: range.start, periodEnd: range.end });

    // 그 상태에서 시작일을 지운다. 되돌릴 기간이 지금과 같아 바깥 값은 그대로다.
    fireEvent.focus(start());
    fireEvent.change(start(), { target: { value: "" } });
    expect(start().getAttribute("aria-invalid")).toBe("true");

    await user.click(screen.getByRole("button", { name: "범위 전체로 되돌리기" }));

    expect(stateCondition().periodStart).toBeUndefined();
    expect(start().value).toBe(range.start);
    expect(start().getAttribute("aria-invalid")).toBe("false");
    expect(screen.queryByText(/저장하지 않았습니다/)).toBeNull();
  });

  it("입력 도중 반영된 날짜는 마지막 날짜가 무효가 되면 입력 전 기간으로 되돌린다", async () => {
    // 날짜 칸은 한 글자마다 바뀜을 보낸다. `31`을 치는 도중의 `03`이 범위 안이라 먼저 반영된다.
    const { onChange } = await renderOpen();
    const start = screen.getByLabelText("시작일") as HTMLInputElement;
    const end = screen.getByLabelText("종료일") as HTMLInputElement;

    // 기간을 먼저 하나 골라 둔다.
    fireEvent.focus(start);
    fireEvent.change(start, { target: { value: "2021-01-04" } });
    expect(stateCondition()).toMatchObject({ periodStart: "2021-01-04" });

    // 새로 입력을 시작한다. 중간 날짜는 범위 안, 마지막 날짜는 범위 밖이다.
    fireEvent.focus(end);
    fireEvent.change(end, { target: { value: "2024-12-03" } });
    expect(stateCondition()).toMatchObject({ periodEnd: "2024-12-03" });
    fireEvent.change(end, { target: { value: "2024-12-31" } });

    expect(stateCondition()).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: range.end,
    });
    expect(storedCondition()).toMatchObject({
      periodStart: "2021-01-04",
      periodEnd: range.end,
    });
    // 입력칸은 사용자가 친 날짜를 그대로 두고, 문제인 칸만 표시한다.
    expect(end.value).toBe("2024-12-31");
    expect(end.getAttribute("aria-invalid")).toBe("true");
    expect(start.getAttribute("aria-invalid")).toBe("false");
    expect(onChange).toHaveBeenCalled();
  });

  it("고르지 않은 상태에서 시작한 입력이 무효로 끝나면 범위 전체로 돌아간다", async () => {
    await renderOpen();
    const start = screen.getByLabelText("시작일") as HTMLInputElement;

    fireEvent.focus(start);
    fireEvent.change(start, { target: { value: "2021-01-04" } });
    expect(stateCondition().periodStart).toBe("2021-01-04");
    fireEvent.change(start, { target: { value: "" } });

    expect(stateCondition().periodStart).toBeUndefined();
    expect(stateCondition().periodEnd).toBeUndefined();
    expect(storedCondition().periodStart).toBeUndefined();
    expect(start.value).toBe("");
  });

  it("기간 입력의 최소·최대가 평가 가능 범위와 같고 범위 밖 날짜는 반영하지 않는다", async () => {
    const { onChange } = await renderOpen();

    const start = screen.getByLabelText("시작일") as HTMLInputElement;
    const end = screen.getByLabelText("종료일") as HTMLInputElement;
    expect(start.min).toBe(range.start);
    expect(start.max).toBe(range.end);
    expect(end.min).toBe(range.start);
    expect(end.max).toBe(range.end);
    expect(start.value).toBe(range.start);

    // 범위보다 하루 이르다. 경계로 잘라 넣지 않는다.
    fireEvent.change(start, { target: { value: "2020-01-01" } });
    expect(onChange).not.toHaveBeenCalled();
    expect(stateCondition().periodStart).toBeUndefined();
    expect(storedCondition().periodStart).toBeUndefined();
    expect(start.value).toBe("2020-01-01");
    expect(
      screen.getByText(
        /범위\(2020-01-02 ~ 2024-12-30\) 밖의 날짜는 고를 수 없습니다/,
      ),
    ).toBeTruthy();

    // 시작이 종료보다 늦은 기간도 받지 않는다.
    fireEvent.change(start, { target: { value: "2022-01-03" } });
    fireEvent.change(end, { target: { value: "2021-01-04" } });
    expect(stateCondition().periodEnd).not.toBe("2021-01-04");

    fireEvent.change(end, { target: { value: "2023-12-28" } });
    expect(onChange).toHaveBeenLastCalledWith({
      periodStart: "2022-01-03",
      periodEnd: "2023-12-28",
    });
    expect(stateCondition()).toMatchObject({
      periodStart: "2022-01-03",
      periodEnd: "2023-12-28",
    });
    expect(storedCondition()).toMatchObject({
      periodStart: "2022-01-03",
      periodEnd: "2023-12-28",
    });
  });

  it("고른 기간을 범위 전체로 되돌리면 두 날짜를 함께 지운다", async () => {
    const { user } = await renderOpen();

    expect(
      screen.queryByRole("button", { name: "범위 전체로 되돌리기" }),
    ).toBeNull();
    fireEvent.change(screen.getByLabelText("시작일"), {
      target: { value: "2021-01-04" },
    });
    await user.click(
      screen.getByRole("button", { name: "범위 전체로 되돌리기" }),
    );

    expect(stateCondition().periodStart).toBeUndefined();
    expect(stateCondition().periodEnd).toBeUndefined();
    expect((screen.getByLabelText("시작일") as HTMLInputElement).value).toBe(
      range.start,
    );
  });

  it.each([
    ["빈 값", ""],
    ["숫자가 아닌 글", "천만원"],
    ["최솟값 미만", String(amount.min - amount.step)],
    ["최댓값 초과", String(amount.max + amount.step)],
    ["간격에 맞지 않는 값", String(amount.min + amount.step / 2)],
  ])("시작 금액이 %s이면 저장되지 않는다", async (_, text) => {
    const { onChange } = await renderOpen();

    fireEvent.change(screen.getByRole("textbox", { name: /시작 금액/ }), {
      target: { value: text },
    });

    expect(onChange).not.toHaveBeenCalled();
    expect(stateCondition().initialAmount).toBeUndefined();
    expect(storedCondition().initialAmount).toBeUndefined();
  });

  it.each([
    ["최솟값과 같으면", amount.min],
    ["최댓값과 같으면", amount.max],
  ])("시작 금액이 %s 허용한다", async (_, value) => {
    const { onChange } = await renderOpen();

    fireEvent.change(screen.getByRole("textbox", { name: /시작 금액/ }), {
      target: { value: String(value) },
    });

    expect(onChange).toHaveBeenLastCalledWith({ initialAmount: value });
    expect(stateCondition().initialAmount).toBe(value);
    expect(storedCondition().initialAmount).toBe(value);
  });

  it("최소 평가 기간을 등록표의 라벨로 보이고 바꾸면 그 값으로 반영한다", async () => {
    const { onChange, user } = await renderOpen();

    const labels = within(screen.getByRole("group", { name: "최소 평가 기간" }))
      .getAllByRole("radio")
      .map((radio) => radio.closest("label")?.textContent);
    expect(labels).toEqual(
      registryFixture.analysisConditions.minEvaluationYears.choices.map(
        (choice) => choice.label,
      ),
    );
    expect(
      (screen.getByRole("radio", { name: "기본(1년)" }) as HTMLInputElement)
        .checked,
    ).toBe(true);

    await user.click(screen.getByRole("radio", { name: "엄격(3년)" }));
    expect(onChange).toHaveBeenLastCalledWith({ minEvaluationYears: 3 });
    expect(stateCondition().minEvaluationYears).toBe(3);

    await user.click(screen.getByRole("radio", { name: "완화(제한 없음)" }));
    expect(onChange).toHaveBeenLastCalledWith({ minEvaluationYears: 0 });
    expect(storedCondition().minEvaluationYears).toBe(0);
  });

  it("비용 수치·버전·임시 여부를 읽기 전용으로 함께 보인다", async () => {
    const { panel } = await renderOpen();

    const group = within(panel).getByRole("group", { name: "비용 가정" });
    const text = group.textContent ?? "";
    expect(text).toContain("수수료 1.5bp");
    expect(text).toContain("호가 차이 5bp");
    expect(text).toContain("슬리피지 2.25bp");
    expect(text).toContain("세금 20bp");
    expect(text).toContain("SYN-COST-1");
    expect(text).toContain("임시값");
    // 비용은 고칠 수단이 없다. 하나뿐인 버튼은 값을 바꾸는 것이 아니라 도움말이다.
    expect(within(group).queryByRole("textbox")).toBeNull();
    expect(within(group).queryByRole("spinbutton")).toBeNull();
    const buttons = within(group).getAllByRole("button");
    expect(buttons).toHaveLength(1);
    expect(buttons[0].getAttribute("aria-label")).toBe("비용 가정 도움말");
  });

  it("비용 가정 도움말에 마우스를 얹으면 어느 기준의 수치인지 말한다", async () => {
    const { panel, user } = await renderOpen();

    // 도움말은 행 머리에 있다. 상세를 펼치지 않아도 닿는다.
    await user.hover(
      within(panel).getByRole("button", { name: "비용 가정 도움말" }),
    );

    expect(await screen.findByText(/2026년 한국 ETF/)).toBeTruthy();
  });

  it("비용 조건을 확인하지 못하면 일반 문구로 대신하지 않고 분석할 수 없다고 알린다", async () => {
    const { panel } = await renderOpen(null);

    const group = within(panel).getByRole("group", { name: "비용 가정" });
    expect(within(group).getByRole("status").textContent).toBe(
      COST_UNAVAILABLE,
    );
    expect(group.textContent).not.toMatch(/수수료|적용됨/);
  });

  it("비용 조건을 조회하는 중이면 확인할 수 없다고 말하지 않고 조회 중임을 알린다", async () => {
    const { panel } = await renderOpen("loading");

    const group = within(panel).getByRole("group", { name: "비용 가정" });
    expect(within(group).getByRole("status").textContent).toBe(COST_LOADING);
    expect(group.textContent).not.toContain(COST_UNAVAILABLE);
  });

  it("수수료·세금 편집, 최적화 선택지가 없고 변동성 제한은 꺼진 채로 시작한다", async () => {
    const { panel } = await renderOpen();

    // 바꿀 수 있는 입력은 기간 둘, 금액 하나, 최소 평가 기간 후보, 변동성 제한 켜고 끄기뿐이다.
    const editable = [
      ...within(panel).queryAllByRole("textbox"),
      ...within(panel).queryAllByRole("spinbutton"),
      ...Array.from(panel.querySelectorAll('input[type="date"]')),
    ];
    expect(editable).toHaveLength(3);
    expect(within(panel).getAllByRole("radio")).toHaveLength(
      registryFixture.analysisConditions.minEvaluationYears.choices.length + 2,
    );
    expect(
      within(panel).queryByLabelText(/수수료|세금|슬리피지|호가/),
    ).toBeNull();
    expect(panel.textContent).not.toMatch(/최적화|수익 극대화|최고 수익/);
    expect(
      within(panel).queryByRole("button", { name: /최적|극대화|추천/ }),
    ).toBeNull();

    expect(
      (within(panel).getByRole("radio", { name: "쓰지 않음" }) as HTMLInputElement).checked,
    ).toBe(true);
    expect(within(panel).queryByRole("combobox", { name: "목표 변동성(연)" })).toBeNull();
    expect(stateCondition()).toMatchObject({ scenario: "FULL_PERIOD" });
    expect(stateCondition().targetVolatility).toBeUndefined();
  });

  it("변동성 제한을 켜면 등록표 기본값으로 시작하고 범위 안의 값만 고르게 하며 끄면 값을 지운다", async () => {
    const { onChange, user, panel } = await renderOpen();
    const volatility = registryFixture.analysisConditions.targetVolatility;

    await user.click(within(panel).getByRole("radio", { name: "씀" }));
    expect(onChange).toHaveBeenLastCalledWith({ targetVolatility: volatility.default });
    expect(stateCondition()).toMatchObject({
      scenario: "TARGET_RISK",
      targetVolatility: volatility.default,
    });

    const select = within(panel).getByRole("combobox", { name: "목표 변동성(연)" });
    const values = within(select)
      .getAllByRole("option")
      .map((option) => Number((option as HTMLOptionElement).value));
    expect(values[0]).toBe(volatility.min);
    expect(values[values.length - 1]).toBe(volatility.max);
    expect(values.every((value, i) => i === 0 || value - values[i - 1] === volatility.step)).toBe(true);

    await user.selectOptions(select, String(volatility.max));
    expect(storedCondition()).toMatchObject({
      scenario: "TARGET_RISK",
      targetVolatility: volatility.max,
    });

    await user.click(within(panel).getByRole("radio", { name: "쓰지 않음" }));
    expect(stateCondition()).toMatchObject({ scenario: "FULL_PERIOD" });
    expect(storedCondition().targetVolatility).toBeUndefined();
  });
});
