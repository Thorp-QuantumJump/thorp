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
import type { TargetWeightRow } from "./ReadOnlyTargetWeights";

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
        onChange={(axisName, value) => settings.setValue(id, axisName, value)}
        onResetAxis={(axisName) => settings.resetAxis(id, axisName)}
        onResetAll={() => settings.resetAll(id)}
      />
    </>
  );
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
  const toggle = await screen.findByRole("button", { name: "전략 설정" });
  expect(toggle.getAttribute("aria-expanded")).toBe("false");
  await user.click(toggle);
  expect(toggle.getAttribute("aria-expanded")).toBe("true");
  return user;
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
  it("접힌 채로 시작해 설정 항목을 그리지 않는다", async () => {
    render(
      <MemoryRouter>
        <Harness strategy={selectableStrategy} />
      </MemoryRouter>,
    );
    await screen.findByRole("button", { name: "전략 설정" });

    for (const axis of selectableStrategy.axes) {
      expect(screen.queryByText(axis.label)).toBeNull();
    }
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
    // 기본값은 추천이 아니라는 문장(FR-004)
    expect(
      screen.getByText(/편의용 출발점이며 최적값이나 투자 권고가 아닙니다/),
    ).not.toBeNull();
  });

  it("사용자 비중 전략은 비중을 읽기 전용으로 보이고 앞 단계로 가는 연결을 둔다", async () => {
    await renderOpened(unselectableStrategy);

    const table = screen.getByRole("table");
    expect(within(table).getByText("합성 섹터 가")).not.toBeNull();
    expect(within(table).queryByRole("textbox")).toBeNull();
    expect(within(table).queryByRole("slider")).toBeNull();

    const link = screen.getByRole("link", {
      name: "섹터·비중 단계로 돌아가세요",
    });
    expect(link.getAttribute("href")).toBe("/diagnosis/sectors");
    expect(
      screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/),
    ).toBeNull();
  });

  it("자동 배분 전략은 출력 비중이 계산 결과라는 설명을 보이고 비중 표를 그리지 않는다", async () => {
    await renderOpened(selectableStrategy);

    expect(
      screen.getByText(
        "출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.",
      ),
    ).not.toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("동일 비중 전략은 비중 표도 자동 배분 설명도 보이지 않는다", async () => {
    await renderOpened(plainStrategy);

    expect(screen.queryByRole("table")).toBeNull();
    expect(
      screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/),
    ).toBeNull();
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
    expect((band as HTMLInputElement).value).toBe(
      String(selectableStrategy.axes[2].defaultValue),
    );

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

  it("패널을 접어도 규칙 위반 안내는 남는다", async () => {
    const user = await renderOpened(selectableStrategy);
    const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });

    await user.clear(band);
    await user.type(band, "15");
    await user.click(screen.getByRole("button", { name: "전략 설정" }));

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

  it("등록표가 termId를 준 축에만 도움말이 붙고, 열면 013 원본 문구가 보이며 값은 그대로 남는다", async () => {
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
    expect(
      screen.queryByRole("button", { name: `${leftGap.label} 도움말` }),
    ).toBeNull();

    const gap = screen.getByRole("textbox", {
      name: new RegExp(leftGap.label),
    });
    await user.clear(gap);
    await user.type(gap, "5");

    await user.click(
      screen.getByRole("button", { name: `${gate.label} 도움말` }),
    );
    expect(fetchGlossaryEntry).toHaveBeenCalledWith(
      "synthetic-gate",
      expect.any(AbortSignal),
    );
    expect(
      await screen.findByText("문은 어느 쪽 값을 쓸지 정하는 용어다."),
    ).not.toBeNull();

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

  it("대응하는 설명이 없으면 도움말은 설명 준비 중만 보인다", async () => {
    const [gate] = conditionalStrategy.axes;
    fetchGlossaryEntry.mockResolvedValue({ status: "notReady" });
    const user = await renderOpened(
      conditionalStrategy,
      withConditionalStrategy(),
    );

    await user.click(
      screen.getByRole("button", { name: `${gate.label} 도움말` }),
    );

    expect(await screen.findByText("설명 준비 중")).not.toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
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
