import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import type { GlossaryLookup } from "@/api/learningGlossary";
import { useStrategySettings } from "@/hooks/useStrategySettings";
import {
  conditionalStrategy,
  plainStrategy,
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
  withConditionalStrategy,
} from "@/test/strategyRegistryFixtures";
import type { RegistryResponse, StrategyView } from "@/types/strategyRegistry";
import { DetailSettingsPanel } from "./DetailSettingsPanel";
import type { TargetWeightRow } from "./TargetWeightEditor";

/**
 * 전략 설정 패널(T056)을 실제 설정 훅과 함께 돌린다(FR-003·FR-006·FR-008·FR-012, quickstart 5-1·6).
 *
 * <p><b>섹터 수를 여섯으로 둔다.</b> 픽스처의 규칙은 "허용 이탈 폭 × 섹터 수 < 100이면 위반"이라,
 * 기본값 35에서 섹터 여섯이면 210으로 통과하고 15로 바꾸면 90으로 위반한다. 기본 설정이 이미 위반인
 * 조건에서는 "바꾸면 생기고 복원하면 사라진다"를 볼 수 없다.
 */

const UNIVERSE_SIZE = 6;

const fetchGlossaryEntry = vi.hoisted(() =>
  vi.fn<(termId: string, signal?: AbortSignal) => Promise<GlossaryLookup>>(),
);

vi.mock("@/api/learningGlossary", () => ({ fetchGlossaryEntry }));

beforeAll(() => {
  // Popover의 위치 계산이 크기 관찰을 쓴다. jsdom에는 없다.
  if (!("ResizeObserver" in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
  }
});

const weights: TargetWeightRow[] = [
  { sectorId: "SEC-SYN-A", name: "합성 섹터 가", weightTenths: 600 },
  { sectorId: "SEC-SYN-B", name: "합성 섹터 나", weightTenths: 400 },
];

/** 비중을 고친 사실만 확인하면 되므로 값은 이 창에 적고 상태로 되돌리지는 않는다. */
const onTargetWeightChange = vi.fn();
const onDistributeWeightsEven = vi.fn();

function Harness({
  strategy,
  registry = registryFixture,
}: {
  strategy: StrategyView;
  registry?: RegistryResponse;
}) {
  const settings = useStrategySettings(registry, UNIVERSE_SIZE);
  const { ready, select } = settings;

  useEffect(() => {
    if (ready) select(strategy.strategyId);
  }, [ready, select, strategy.strategyId]);

  if (!settings.selectedStrategy || !settings.values) return null;
  const id = settings.selectedStrategy.strategyId;

  return (
    <>
      {/* 상태에 실제로 들어간 값. 범위 밖 입력이 걸러졌는지 보는 창이다. */}
      <output data-testid="stored-values">
        {JSON.stringify(settings.values)}
      </output>
      <DetailSettingsPanel
        strategy={settings.selectedStrategy}
        values={settings.values}
        universeSize={UNIVERSE_SIZE}
        targetWeights={weights}
        onTargetWeightChange={onTargetWeightChange}
        onDistributeWeightsEven={onDistributeWeightsEven}
        evaluationRange={{ start: "2019-06-03", end: "2024-12-30" }}
        onChange={(axisName, value) => settings.setValue(id, axisName, value)}
        onResetAxis={(axisName) => settings.resetAxis(id, axisName)}
        onResetAll={() => settings.resetAll(id)}
      />
    </>
  );
}

/** 등록표에서 전략 하나만 바꿔 끼운다. 축 구성이 다른 경우를 보려고 쓴다. */
function registryWith(strategy: StrategyView): RegistryResponse {
  return {
    ...registryFixture,
    strategies: registryFixture.strategies.map((candidate) =>
      candidate.strategyId === strategy.strategyId ? strategy : candidate,
    ),
  };
}

async function renderOpened(
  strategy: StrategyView,
  registry?: RegistryResponse,
) {
  const user = userEvent.setup();
  render(
    <MemoryRouter>
      <Harness strategy={strategy} registry={registry} />
    </MemoryRouter>,
  );
  const region = await screen.findByRole("region", { name: "전략 설정" });
  await openAllRows(user, region);
  return user;
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


let fetchSpy: ReturnType<typeof vi.spyOn>;
let xhrSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  window.sessionStorage.clear();
  fetchGlossaryEntry.mockReset();
  fetchSpy = vi.spyOn(globalThis, "fetch");
  xhrSpy = vi.spyOn(XMLHttpRequest.prototype, "open");
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("DetailSettingsPanel", () => {
  it("후보가 몇 개뿐인 목록형은 바로 고르고, 숫자 축은 값만 보이다가 눌러야 입력칸이 열린다", async () => {
    render(
      <MemoryRouter>
        <Harness strategy={selectableStrategy} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    for (const axis of selectableStrategy.axes) {
      if (axis.inputType === "CHOICE") {
        // 등록표 후보 그대로 알약이 선다(FR-005). 펼칠 까닭이 없어 여는 단추가 없다.
        expect(
          within(region).getByRole("group", { name: axis.label }),
        ).toBeTruthy();
        expect(rowToggle(axis.label, region)).toBeNull();
      } else {
        expect(rowToggle(axis.label, region)?.getAttribute("aria-expanded")).toBe("false");
      }
    }
    // 숫자 입력칸은 펼치기 전에는 없다.
    expect(within(region).queryByRole("textbox")).toBeNull();
    // 바꾼 것이 없으면 되돌리기가 하나도 없다. '모두 기본값 복원'도 바꾼 값이 생겨야 나타난다.
    expect(within(region).queryAllByRole("button", { name: /기본값 복원/ })).toHaveLength(0);
  });

  it("후보가 넷을 넘는 목록형 축은 값을 눌러 펼치고, 넷까지는 행 오른쪽에서 바로 고른다", async () => {
    const drift = selectableStrategy.axes.find((axis) => axis.name === "driftWindow")!;
    const five = {
      ...drift,
      choices: [15, 30, 45, 60, 90].map((days) => ({
        value: days,
        label: `${days}거래일`,
        selectable: true,
      })),
    };
    const wide = { ...selectableStrategy, axes: selectableStrategy.axes.map((axis) => (axis.name === drift.name ? five : axis)) };
    const registry = {
      ...registryFixture,
      strategies: registryFixture.strategies.map((strategy) =>
        strategy.strategyId === wide.strategyId ? wide : strategy,
      ),
    };
    render(
      <MemoryRouter>
        <Harness strategy={wide} registry={registry} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    // 다섯 후보는 펼쳐야 보인다. 접힌 행은 지금 값을 등록표 라벨로 보인다.
    const user = userEvent.setup();
    const toggle = rowToggle(drift.label, region)!;
    expect(toggle).not.toBeNull();
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(toggle.textContent).toContain("45거래일");
    expect(within(region).queryByRole("group", { name: drift.label })).toBeNull();
    await user.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(within(region).getAllByRole("radio", { name: /거래일$/ }).length).toBeGreaterThanOrEqual(5);

    // 펼친 자리에서 고르면 접힌 값도 바뀌고, 되돌리면 등록표 기본값의 라벨로 돌아오며 초점은 그 행에 남는다.
    await user.click(within(region).getByRole("radio", { name: "90거래일" }));
    expect(toggle.textContent).toContain("90거래일");
    await user.click(
      within(region).getByRole("button", { name: `${drift.label} 기본값 복원` }),
    );
    expect(toggle.textContent).toContain("45거래일");
    expect(
      within(region).queryByRole("button", { name: `${drift.label} 기본값 복원` }),
    ).toBeNull();
    expect(document.activeElement).toBe(toggle);

    // 후보가 둘인 축은 여전히 행 오른쪽에 바로 있다.
    const resetTiming = selectableStrategy.axes.find((axis) => axis.name === "resetTiming")!;
    expect(within(region).getByRole("group", { name: resetTiming.label })).toBeTruthy();
    expect(rowToggle(resetTiming.label, region)).toBeNull();
  });

  it("후보가 정확히 넷인 목록형 축은 펼치지 않고 행 오른쪽에서 바로 고른다", async () => {
    // 펼침의 경계(INLINE_CHOICE_LIMIT = 4)를 못 박는다. 셋이나 다섯만 보면 경계를 옮겨도 통과한다.
    const drift = selectableStrategy.axes.find((axis) => axis.name === "driftWindow")!;
    const four = {
      ...drift,
      choices: [15, 30, 45, 60].map((days) => ({
        value: days,
        label: `${days}거래일`,
        selectable: true,
      })),
    };
    const edged = {
      ...selectableStrategy,
      axes: selectableStrategy.axes.map((axis) => (axis.name === drift.name ? four : axis)),
    };
    render(
      <MemoryRouter>
        <Harness strategy={edged} registry={registryWith(edged)} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    const group = within(region).getByRole("group", { name: drift.label });
    expect(within(group).getAllByRole("radio")).toHaveLength(4);
    expect(rowToggle(drift.label, region)).toBeNull();
  });

  it("후보 라벨에 단위가 없는 긴 목록 행은 펼침 단추 이름에도 단위를 담는다", async () => {
    // 행 이름 옆에 '(거래일)'이 보이면 음성으로도 같은 이름으로 불려야 한다(WCAG 2.5.3).
    const drift = selectableStrategy.axes.find((axis) => axis.name === "driftWindow")!;
    const bare = {
      ...drift,
      choices: [15, 30, 45, 60, 90].map((days) => ({
        value: days,
        label: String(days),
        selectable: true,
      })),
    };
    const strategy = {
      ...selectableStrategy,
      axes: selectableStrategy.axes.map((axis) => (axis.name === drift.name ? bare : axis)),
    };
    render(
      <MemoryRouter>
        <Harness strategy={strategy} registry={registryWith(strategy)} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    expect(
      within(region).getByRole("button", { name: `${drift.label} (${drift.unit}) 45` }),
    ).toBe(rowToggle(drift.label, region));
  });

  it("허용 범위를 받지 못한 입력형 축은 펼칠 자리 없이 그 사실을 늘 보인다", async () => {
    const band = selectableStrategy.axes.find((axis) => axis.inputType === "NUMBER")!;
    const broken = {
      ...selectableStrategy,
      axes: selectableStrategy.axes.map((axis) => (axis.name === band.name ? { ...axis, range: undefined } : axis)),
    };
    const registry = {
      ...registryFixture,
      strategies: registryFixture.strategies.map((strategy) =>
        strategy.strategyId === broken.strategyId ? broken : strategy,
      ),
    };
    render(
      <MemoryRouter>
        <Harness strategy={broken} registry={registry} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    expect(
      within(region).getByText("이 항목의 허용 범위를 확인하지 못해 값을 바꿀 수 없습니다."),
    ).toBeTruthy();
    expect(rowToggle(band.label, region)).toBeNull();
  });

  it("'모두 기본값 복원'은 바꾼 값이 있을 때만 묶음 제목 옆에 나타난다", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Harness strategy={selectableStrategy} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    expect(within(region).queryByRole("button", { name: "모두 기본값 복원" })).toBeNull();
    await user.click(within(region).getByRole("radio", { name: "90거래일" }));
    const title = within(region).getByRole("heading", { name: "전략 설정" });
    const resetAll = within(region).getByRole("button", { name: "모두 기본값 복원" });
    // 묶음 제목과 같은 줄이다.
    expect(title.parentElement?.contains(resetAll)).toBe(true);
    await user.click(resetAll);
    expect(
      (within(region).getByRole("radio", { name: "45거래일" }) as HTMLInputElement).checked,
    ).toBe(true);
    expect(within(region).queryByRole("button", { name: "모두 기본값 복원" })).toBeNull();
    // 누른 단추가 사라지므로 초점은 묶음 제목이 이어받는다. 문서 맨 앞으로 떨어지지 않는다(NFR-001).
    expect(document.activeElement).toBe(title);
  });

  it("항목이 하나뿐인 전략은 값을 바꿔도 '모두 기본값 복원'을 두지 않고 그 행의 되돌리기만 둔다", async () => {
    const user = userEvent.setup();
    const drift = selectableStrategy.axes.find((axis) => axis.name === "driftWindow")!;
    const single = { ...selectableStrategy, axes: [drift], validationRules: [] };
    render(
      <MemoryRouter>
        <Harness strategy={single} registry={registryWith(single)} />
      </MemoryRouter>,
    );
    const region = await screen.findByRole("region", { name: "전략 설정" });

    await user.click(within(region).getByRole("radio", { name: "90거래일" }));
    expect(within(region).queryByRole("button", { name: "모두 기본값 복원" })).toBeNull();
    expect(
      within(region).getByRole("button", { name: `${drift.label} 기본값 복원` }),
    ).toBeTruthy();
  });

  it("저장되지 않은 범위 밖 입력도 '모두 기본값 복원'이 지운다", async () => {
    // 범위 밖 값은 상태에 들어가지 않아 그 행에는 되돌리기가 없다. 묶음 전체 복원이 입력칸까지 맞춰야 한다.
    const user = await renderOpened(selectableStrategy);
    await user.click(screen.getByRole("radio", { name: "90거래일" }));
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });
    await user.clear(band);
    await user.type(band, "55");
    expect(screen.getByText(/허용 범위 밖입니다/)).not.toBeNull();

    await user.click(screen.getByRole("button", { name: "모두 기본값 복원" }));

    // 행은 펼친 채 남고, 입력칸이 기본값으로 다시 맞춰진다.
    expect(
      (screen.getByRole("textbox", { name: /허용 이탈 폭/ }) as HTMLInputElement).value,
    ).toBe(String(selectableStrategy.axes[2].defaultValue));
    expect(screen.queryByText(/허용 범위 밖입니다/)).toBeNull();
  });

  it("숫자 항목을 바꾸면 그 행에만 되돌리기가 나타나고, 누르면 기본값으로 돌아가며 사라진다", async () => {
    const user = await renderOpened(selectableStrategy);
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });
    await user.clear(band);
    await user.type(band, "20");

    expect(
      screen.getByRole("button", { name: "허용 이탈 폭 기본값 복원" }),
    ).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "표류 관측 창 기본값 복원" }),
    ).toBeNull();

    await user.click(screen.getByRole("button", { name: "허용 이탈 폭 기본값 복원" }));

    expect(
      (screen.getByRole("textbox", { name: /허용 이탈 폭/ }) as HTMLInputElement).value,
    ).toBe(String(selectableStrategy.axes[2].defaultValue));
    expect(
      screen.queryByRole("button", { name: "허용 이탈 폭 기본값 복원" }),
    ).toBeNull();
    // 아이콘이 사라진 뒤 초점은 그 행의 펼침 단추가 받는다.
    expect(document.activeElement).toBe(rowToggle("허용 이탈 폭"));
  });

  it("선택한 전략의 축만 그리고 다른 전략의 축은 그리지 않는다", async () => {
    await renderOpened(selectableStrategy);

    for (const axis of selectableStrategy.axes) {
      expect(screen.getByText(axis.label)).not.toBeNull();
    }
    for (const other of [unselectableStrategy, plainStrategy]) {
      for (const axis of other.axes)
        expect(screen.queryByText(axis.label)).toBeNull();
    }
  });

  it("사용자 비중 전략은 비중을 고칠 수 있게 보이고 앞 단계로 가는 연결을 둔다", async () => {
    await renderOpened(unselectableStrategy);

    const region = screen.getByRole("region", { name: "목표 비중" });
    expect(within(region).getByText("합성 섹터 가 비중")).not.toBeNull();
    // 값을 정하는 수단이 둘이고 가리키는 값은 하나다.
    expect(within(region).getAllByRole("slider")).toHaveLength(weights.length);
    expect(within(region).getAllByRole("spinbutton")).toHaveLength(
      weights.length,
    );

    // 담을 대상 자체는 앞 단계가 소유한다.
    const link = screen.getByRole("link", {
      name: "투자 대상 고르기로 돌아가세요",
    });
    expect(link.getAttribute("href")).toBe("/diagnosis/sectors");
    expect(
      screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/),
    ).toBeNull();
  });

  it("비중을 고치면 그 값이 올라온다", async () => {
    const user = userEvent.setup();
    await renderOpened(unselectableStrategy);

    const region = screen.getByRole("region", { name: "목표 비중" });
    const field = within(region).getAllByRole("spinbutton")[0];
    await user.clear(field);
    await user.type(field, "55.5");

    expect(onTargetWeightChange).toHaveBeenLastCalledWith("SEC-SYN-A", 555);
  });

  it("자동 배분·동일 비중 전략은 비중 편집기를 두지 않는다", async () => {
    // 출력 비중이 계산 결과라는 설명(FR-012)과 기본값이 출발점이라는 말(FR-004)은 상세 설정 카드 머리가
    // 보인다(페이지 검사). 이 묶음은 전략 설정 행만 그린다.
    await renderOpened(selectableStrategy);
    expect(screen.queryByRole("region", { name: "목표 비중" })).toBeNull();
  });

  it("동일 비중 전략은 비중 편집기를 두지 않는다", async () => {
    await renderOpened(plainStrategy);

    expect(screen.queryByRole("region", { name: "목표 비중" })).toBeNull();
  });

  it("규칙을 어기는 값은 즉시 등록표 문구로 알리고, 모두 복원하면 사라지며 서버에 묻지 않는다", async () => {
    const user = await renderOpened(selectableStrategy);
    const rule = selectableStrategy.validationRules[0];
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });

    expect(screen.queryByRole("alert")).toBeNull();

    await user.clear(band);
    await user.type(band, "15");

    // 15 × 6 = 90 < 100 — 범위 안이지만 규칙 위반이다(quickstart 5-1).
    const alert = screen.getByRole("alert");
    expect(alert.textContent).toBe(rule.detail);

    await user.click(screen.getByRole("button", { name: "모두 기본값 복원" }));

    expect(screen.queryByRole("alert")).toBeNull();
    // 펼친 폼은 새로 그려진다. 같은 행의 새 입력칸이 기본값을 보인다.
    expect(
      (screen.getByRole("textbox", { name: /허용 이탈 폭/ }) as HTMLInputElement).value,
    ).toBe(String(selectableStrategy.axes[2].defaultValue));

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
  });

  it("범위 밖 값은 상태에 들어가지 않는다", async () => {
    const user = await renderOpened(selectableStrategy);
    const axis = selectableStrategy.axes[2];
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });

    await user.clear(band);
    await user.type(band, "55");

    expect(screen.getByText(/허용 범위 밖입니다/)).not.toBeNull();
    const stored = JSON.parse(
      screen.getByTestId("stored-values").textContent ?? "{}",
    );
    expect(stored[axis.name]).toEqual(axis.defaultValue);
  });

  it("행을 접어도 규칙 위반 안내는 남는다", async () => {
    const user = await renderOpened(selectableStrategy);
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });

    await user.clear(band);
    await user.type(band, "15");
    await user.click(rowToggle("허용 이탈 폭")!);

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.getByRole("alert").textContent).toBe(
      selectableStrategy.validationRules[0].detail,
    );
  });

  it("조건 밖 축은 그리지 않고, 결정 축을 바꾸면 그 자리에서 바뀐다", async () => {
    const [gate, leftGap, rightPick] = conditionalStrategy.axes;
    const user = await renderOpened(
      conditionalStrategy,
      withConditionalStrategy(),
    );

    expect(screen.getByText(leftGap.label)).not.toBeNull();
    expect(screen.queryByText(rightPick.label)).toBeNull();

    await user.click(screen.getByRole("radio", { name: "오른쪽" }));

    expect(screen.queryByText(leftGap.label)).toBeNull();
    expect(screen.getByText(rightPick.label)).not.toBeNull();
    // 새로 열린 축 자체는 기본값에서 시작한다.
    expect(
      (screen.getByRole("radio", { name: "하나" }) as HTMLInputElement).checked,
    ).toBe(true);
    expect(
      screen.getByRole("group", { name: new RegExp(gate.label) }),
    ).not.toBeNull();
  });

  it("조건 밖으로 밀려난 축의 규칙 위반은 함께 사라진다", async () => {
    const [, leftGap] = conditionalStrategy.axes;
    const rule = conditionalStrategy.validationRules[0];
    const user = await renderOpened(
      conditionalStrategy,
      withConditionalStrategy(),
    );

    const gap = screen.getByRole("textbox", {
      name: new RegExp(leftGap.label),
    });
    await user.clear(gap);
    await user.type(gap, "9");
    expect(screen.getByRole("alert").textContent).toBe(rule.detail);

    await user.click(screen.getByRole("radio", { name: "오른쪽" }));

    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("termId 가 있는 축은 013 문구를, 없는 축은 등록표 설명을 도움말로 보이며 값은 그대로 남는다", async () => {
    const [gate, leftGap] = conditionalStrategy.axes;
    fetchGlossaryEntry.mockResolvedValue({
      status: "ready",
      entry: {
        termId: "synthetic-gate",
        label: "문",
        sentence: "문은 어느 쪽 값을 쓸지 정하는 용어다.",
        articleId: "synthetic-article",
        articleTitle: "합성 글",
      },
    });
    const user = await renderOpened(
      conditionalStrategy,
      withConditionalStrategy(),
    );

    expect(
      screen.getByRole("button", { name: `${gate.label} 도움말` }),
    ).not.toBeNull();

    // termId 가 없는 축에도 도움말은 있다. 다만 013 용어집이 아니라 등록표의 축 설명이고,
    // 그래서 용어집을 부르지 않는다.
    await user.hover(
      screen.getByRole("button", { name: `${leftGap.label} 도움말` }),
    );
    expect(await screen.findByText(leftGap.description ?? "")).not.toBeNull();
    expect(fetchGlossaryEntry).not.toHaveBeenCalled();

    const gap = screen.getByRole("textbox", {
      name: new RegExp(leftGap.label),
    });
    await user.clear(gap);
    await user.type(gap, "5");

    await user.hover(
      screen.getByRole("button", { name: `${gate.label} 도움말` }),
    );
    // 얹고 나서 열리기까지 잠깐 뜸을 들이므로, 먼저 보이는 것을 기다린 뒤 호출을 본다.
    expect(
      await screen.findByText("문은 어느 쪽 값을 쓸지 정하는 용어다."),
    ).not.toBeNull();
    expect(fetchGlossaryEntry).toHaveBeenCalledWith(
      "synthetic-gate",
      expect.any(AbortSignal),
    );

    await user.keyboard("{Escape}");

    expect(
      screen.queryByText("문은 어느 쪽 값을 쓸지 정하는 용어다."),
    ).toBeNull();
    expect(
      (
        screen.getByRole("textbox", {
          name: new RegExp(leftGap.label),
        }) as HTMLInputElement
      ).value,
    ).toBe("5");
  });

  it("013 에 실린 설명이 없으면 등록표 설명만 보이고 준비 중을 덧붙이지 않는다", async () => {
    const [gate] = conditionalStrategy.axes;
    fetchGlossaryEntry.mockResolvedValue({ status: "notReady" });
    const user = await renderOpened(
      conditionalStrategy,
      withConditionalStrategy(),
    );

    await user.hover(
      screen.getByRole("button", { name: `${gate.label} 도움말` }),
    );

    // 읽을 것이 이미 있으면 "준비 중"을 덧붙이지 않는다 — 방금 읽은 문장이 임시처럼 보인다.
    expect(await screen.findByText(gate.description ?? "")).not.toBeNull();
    expect(screen.queryByText("설명 준비 중")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("워밍업을 이루는 축에는 평가할 수 있는 범위를 적고 도움말이 그 사실을 말한다", async () => {
    // 등록표 warmupRule.sum 이 정하는 축이다. 화면이 축 이름을 적어 두지 않는다(FR-005).
    const user = await renderOpened(selectableStrategy);
    const drift = selectableStrategy.axes.find(
      (axis) => axis.name === "driftWindow",
    )!;

    expect(
      screen.getByText(
        "평가 가능 범위 2019-06-03 ~ 2024-12-30",
      ),
    ).toBeTruthy();

    await user.hover(
      screen.getByRole("button", { name: `${drift.label} 도움말` }),
    );
    expect(
      await screen.findByText(
        /이 값을 바꾸면 평가할 수 있는 범위가 달라집니다/,
      ),
    ).toBeTruthy();
  });

  it("이어진 워밍업 축 묶음에는 평가 가능 범위를 마지막 행 아래에 한 번만 적는다", async () => {
    // 맥박 관측 창과 직전 구간 제외 기간이 함께 워밍업을 이룬다(warmupRule.sum). 같은 날짜를 두 번 적지 않는다.
    await renderOpened(unselectableStrategy);

    const lines = screen.getAllByText("평가 가능 범위 2019-06-03 ~ 2024-12-30");
    expect(lines).toHaveLength(1);
    const lastRow = screen
      .getByRole("group", { name: "직전 구간 제외 기간" })
      .closest('[tabindex="-1"]');
    expect(lastRow?.contains(lines[0])).toBe(true);
  });

  it("도움말은 초점을 받아도 열린다 — 키보드와 터치에서 닿는 길이다", async () => {
    // 얹기로만 열면 마우스가 없는 환경에서 읽을 방법이 사라진다. 트리거를 버튼으로 둔 까닭이다.
    await renderOpened(selectableStrategy);
    const drift = selectableStrategy.axes.find(
      (axis) => axis.name === "driftWindow",
    )!;
    const trigger = screen.getByRole("button", {
      name: `${drift.label} 도움말`,
    });

    expect(
      screen.queryByText(drift.description ?? "", { exact: false }),
    ).toBeNull();
    trigger.focus();

    expect(trigger).toBe(document.activeElement);
    // 워밍업 축이라 등록표 설명 뒤에 범위 안내가 한 문장 더 붙는다. 앞머리로 찾는다.
    expect(
      await screen.findByText(drift.description ?? "", { exact: false }),
    ).toBeTruthy();
  });

  it("목록형 항목을 바꾼 뒤 항목별 복원으로 되돌린다", async () => {
    const user = await renderOpened(selectableStrategy);

    await user.click(screen.getByRole("radio", { name: "90거래일" }));

    await user.click(
      screen.getByRole("button", { name: "표류 관측 창 기본값 복원" }),
    );
    expect(
      (screen.getByRole("radio", { name: "45거래일" }) as HTMLInputElement)
        .checked,
    ).toBe(true);
  });
});
