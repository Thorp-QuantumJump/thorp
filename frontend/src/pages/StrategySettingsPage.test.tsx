import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SectorCatalogResult } from "@/api/sectors";
import { AuthContext, type AuthContextValue } from "@/auth/auth-context";
import {
  type AnalysisSupport,
  type AnalysisVersions,
  type WarmupJudgement,
} from "@/components/strategy/analysisSupport";
import { connectedAnalysisSupport } from "@/components/strategy/connectedAnalysisSupport";
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
} from "@/diagnosis/selectionStorage";
import { STRATEGY_SETTINGS_STORAGE_KEY } from "@/hooks/useStrategySettings";
import {
  plainStrategy,
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from "@/test/strategyRegistryFixtures";
import type { BacktestCreateRequest } from "@/types/backtestRequest";
import type { EvaluationWindow } from "@/types/evaluationWindow";
import type { StrategyView } from "@/types/strategyRegistry";
import type { SectorSummary } from "@/types/sector";
import { StrategySettingsPage } from "./StrategySettingsPage";

/**
 * 카드 선택 한 번과 '분석하기' 한 번으로 요청이 만들어지는지 고정한다(SC-001·SC-005·SC-008,
 * quickstart 4·9).
 *
 * <p><b>등록표 픽스처의 전략과 축은 등록표에 없는 이름이다.</b> 실제 이름을 쓰면 화면이 그 전략을
 * 알고 있어도 검사가 통과한다 — 지어낸 이름이라야 "응답만 보고 그린다"가 증명된다(FR-005).
 *
 * <p><b>분석 조건 버전과 평가 기간은 흉내를 넘긴다.</b> 둘 다 아직 없는 통로이며(①·②) 화면은 이를
 * props로 받는다. 기본값(연결되지 않은 상태)에서 '분석하기'가 정직하게 막히는 것도 함께 본다.
 */

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchStrategyRegistry: vi.fn(),
  // 기본 통로(`defaultAnalysisSupport`)가 부르는 실제 조회의 자리. 통로를 넘기는 검사는 쓰지 않는다.
  fetchEvaluationWindow: vi.fn(),
}));

vi.mock("@/api/sectors", async () => {
  const actual =
    await vi.importActual<typeof import("@/api/sectors")>("@/api/sectors");
  return { ...actual, ...apiMocks };
});

// 기본 통로가 부르는 004 조회·전송의 자리. 통로를 넘기는 검사는 쓰지 않는다.
const backtestMocks = vi.hoisted(() => ({
  fetchAnalysisVersions: vi.fn(),
  createBacktest: vi.fn(),
}));

vi.mock("@/api/analysisVersions", () => ({
  fetchAnalysisVersions: backtestMocks.fetchAnalysisVersions,
}));

vi.mock("@/api/backtest", async () => {
  const actual =
    await vi.importActual<typeof import("@/api/backtest")>("@/api/backtest");
  return { ...actual, createBacktest: backtestMocks.createBacktest };
});

vi.mock("@/api/strategyRegistry", async () => {
  const actual = await vi.importActual<typeof import("@/api/strategyRegistry")>(
    "@/api/strategyRegistry",
  );
  return { ...actual, ...apiMocks };
});

function sector(code: string, name: string): SectorSummary {
  return {
    code,
    name,
    classification: "INDUSTRY",
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: "2018-09-12",
      benchmarkIndex: `KRX ${name}`,
    },
  };
}

const catalogSectors = [
  sector("ALPHA", "가섹터"),
  sector("BETA", "나섹터"),
  sector("GAMMA", "다섹터"),
];

const loadedCatalog: SectorCatalogResult = {
  status: "loaded",
  catalog: {
    mapVersion: "SEM-TEST-1",
    isSample: false,
    sectors: catalogSectors,
  },
};

/** 002가 마친 선택을 그 계약 형식대로 보관한다. */
function storeSelection(codes: string[] = ["ALPHA", "BETA", "GAMMA"]) {
  const share = Math.floor(1000 / codes.length);
  window.sessionStorage.setItem(
    SECTOR_SELECTION_STORAGE_KEY,
    JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: "SEM-TEST-1",
      selected: codes.map((code, index) => ({
        code,
        weightTenths: index === 0 ? 1000 - share * (codes.length - 1) : share,
      })),
      weightsTouched: true,
    }),
  );
}

/** 004 등록표 흉내. 픽스처의 전략은 모두 등록되어 있고, 시나리오 규칙 행이 섞여 있다. */
const RULES_VERSION = "RULES-TEST-1";
const versionsFixture: AnalysisVersions = {
  strategyRules: [
    ...registryFixture.strategies.map((strategy) => ({
      strategyId: strategy.strategyId,
      rulesVersion: RULES_VERSION,
      status: "REGISTERED",
      provisional: true,
    })),
    {
      strategyId: "TARGET_RISK_OVERLAY",
      rulesVersion: "1",
      status: "REGISTERED",
      provisional: true,
    },
  ],
  costProfileVersion: "PROVISIONAL-DEMO-1",
  costProfileProvisional: true,
  costRates: { commissionBps: 1.5, spreadBps: 5, slippageBps: 2, taxBps: 20 },
};

const windowFixture = { start: "2020-01-02", end: "2024-12-30" };

/**
 * 화면이 바깥에서 받는 세 통로의 흉내.
 *
 * 보낸 요청은 `requests`에 그대로 쌓는다 — 모의 함수의 호출 기록에서 꺼내는 것보다 어느 검사가
 * 무엇을 견주는지 읽기 쉽고, 요청 본문의 타입도 그대로 남는다.
 */
function makeSupport(overrides: Partial<AnalysisSupport> = {}) {
  const requests: BacktestCreateRequest[] = [];
  const createBacktest: AnalysisSupport["createBacktest"] = async (request) => {
    requests.push(request);
    return { backtestId: "BT-20260916-000001", status: "QUEUED" as const };
  };
  const judgeWarmup: AnalysisSupport["judgeWarmup"] = (): WarmupJudgement => ({
    verdict: { state: "ok" },
    window: windowFixture,
    reduction: null,
  });
  const fetchAnalysisVersions: AnalysisSupport["fetchAnalysisVersions"] =
    async () => versionsFixture;
  // 판정은 위 `judgeWarmup` 흉내가 하므로 조회 결과는 쓰이지 않는다. 네트워크에 닿지 않고 실패만 한다.
  const fetchEvaluationWindow: AnalysisSupport["fetchEvaluationWindow"] =
    async () => {
      throw new Error("평가 기간 조회를 흉내 내지 않았습니다.");
    };

  const support: AnalysisSupport = {
    fetchAnalysisVersions,
    fetchEvaluationWindow,
    judgeWarmup,
    createBacktest,
    ...overrides,
  };
  return { support, requests };
}

function renderPage(support?: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: "anonymous",
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={["/strategy"]}>
      <AuthContext.Provider value={authValue}>
        <StrategySettingsPage support={support} />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** 합계가 100%에서 0.1%p 모자란 선택을 002 계약 형식대로 보관한다. 002는 이 상태도 정상으로 보관한다. */
function storeUnbalancedSelection() {
  window.sessionStorage.setItem(
    SECTOR_SELECTION_STORAGE_KEY,
    JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: "SEM-TEST-1",
      selected: [
        { code: "ALPHA", weightTenths: 333 },
        { code: "BETA", weightTenths: 333 },
        { code: "GAMMA", weightTenths: 333 },
      ],
      weightsTouched: true,
    }),
  );
}

/** 앞 단계로 이동하는 버튼이 실제로 그 경로에 닿는지 보려고 목적지 화면을 흉내 낸다. */
function renderPageWithSectorRoute(support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: "anonymous",
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={["/strategy"]}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route
            path="/strategy"
            element={<StrategySettingsPage support={support} />}
          />
          <Route
            path="/diagnosis/sectors"
            element={<p>섹터·비중 단계 화면</p>}
          />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/**
 * 자동 비교로 넘어간 주소를 읽으려고 007 화면 자리에 지금 위치를 그대로 보이는 대역을 둔다.
 * 007 화면 자체를 띄우지 않는 까닭은 이 검사가 **무엇을 넘기는가**만 보기 때문이다 — 넘긴 질의
 * 문자열을 007이 어떻게 읽는지는 `ParamSearchPage.test.tsx`가 고정한다.
 */
function ParamSearchProbe() {
  const location = useLocation();
  return (
    <p data-testid="param-search-location">
      {location.pathname + location.search}
    </p>
  );
}

function renderPageWithParamSearchRoute(support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: "anonymous",
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={["/strategy"]}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route
            path="/strategy"
            element={<StrategySettingsPage support={support} />}
          />
          <Route path="/param-search" element={<ParamSearchProbe />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/**
 * 접수 뒤 옮겨 간 결과 주소를 읽으려고 005 화면 자리에 지금 위치를 그대로 보이는 대역을 둔다.
 * 005 화면 자체를 띄우지 않는 까닭은 이 검사가 **어느 주소로 옮기는가**만 보기 때문이다.
 */
function ResultProbe() {
  const location = useLocation();
  return <p data-testid="result-location">{location.pathname}</p>;
}

function renderPageWithResultRoute(support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: "anonymous",
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={["/strategy"]}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route
            path="/strategy"
            element={<StrategySettingsPage support={support} />}
          />
          <Route path="/results/:token" element={<ResultProbe />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/**
 * 사용자 비중을 쓰는 전략을 섹터 셋에서 고를 수 있게 만든 응답.
 *
 * <p><b>카드만 고를 수 있게 바꾸지 않고 기본값도 규칙에 맞춘다.</b> 픽스처의 기본값(고를 섹터 수 4)은
 * 섹터 셋에서 규칙을 어기고, 화면은 규칙 위반이면 '분석하기'를 막는다(T058). 서버는 기본 설정이
 * 규칙을 어기는 전략을 고를 수 있다고 내리지 않으므로, 둘을 함께 맞춰야 실제로 올 수 있는 응답이다.
 */
function selectableUserWeighted(): StrategyView {
  const userWeighted = registryFixture.strategies.find(
    (strategy) => strategy.targetWeightSource === "USER",
  )!;
  return {
    ...userWeighted,
    selectable: true,
    unselectableReason: undefined,
    axes: userWeighted.axes.map((axis) => {
      if (axis.inputType !== "CHOICE") return axis;
      const fitting = axis.choices?.filter((choice) => choice.selectable) ?? [];
      const defaultFits = fitting.some(
        (choice) => choice.value === axis.defaultValue,
      );
      return defaultFits || fitting.length === 0
        ? axis
        : { ...axis, defaultValue: fitting[fitting.length - 1].value };
    }),
  };
}

/** 행동 버튼에 이어진 이유 문장. 이유가 없으면 빈 문자열이다. */
function reasonOf(button: HTMLElement): string {
  const reasonId = button.getAttribute("aria-describedby");
  return reasonId ? (document.getElementById(reasonId)?.textContent ?? "") : "";
}

/**
 * 부모를 다시 그릴 수 있는 호스트로 화면을 띄운다.
 *
 * `makeSupportFor`는 부모가 그릴 때마다 불리며 몇 번째로 다시 그렸는지(`generation`)를 받는다.
 * 통로를 렌더마다 새로 만드는 호출자를 흉내 내려는 것이다 — 이 화면의 상태 변화는 부모를 다시
 * 그리지 않으므로, 부모 쪽 사정으로 다시 그리는 경우는 이렇게 따로 만들어야 검사가 닿는다.
 */
function renderHost(makeSupportFor: (generation: number) => AnalysisSupport) {
  function Host() {
    const [generation, bump] = useState(0);
    const support = makeSupportFor(generation);
    return (
      <>
        <button type="button" onClick={() => bump((count) => count + 1)}>
          부모 다시 그리기
        </button>
        <StrategySettingsPage support={support} />
      </>
    );
  }

  const authValue: AuthContextValue = {
    status: "anonymous",
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={["/strategy"]}>
      <AuthContext.Provider value={authValue}>
        <Host />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/**
 * 카드와 행동 버튼의 접근 가능 이름은 이름·설명·사유를 이어 붙인 글이라 전체 일치로는 찾을 수 없다.
 * 정규식이 아니라 함수로 견주는 까닭은 전략 이름에 괄호 같은 정규식 기호가 들어 있을 수 있기
 * 때문이다(`똑같이 나누기(시험용)`).
 */
async function findCardNamed(name: string): Promise<HTMLElement> {
  return screen.findByRole("radio", {
    name: (accessibleName) => accessibleName.includes(name),
  });
}

function actionButton(label: string): HTMLElement {
  return screen.getByRole("button", {
    name: (accessibleName) => accessibleName.includes(label),
  });
}

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

/**
 * 설정 행 하나를 펼친다. 후보가 몇 개뿐인 행은 행 오른쪽에 이미 선택지가 있어 여는 단추가 없으므로 그대로 둔다.
 */
async function openSetting(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
) {
  const toggle = rowToggle(label);
  if (toggle?.getAttribute("aria-expanded") === "false") await user.click(toggle);
}

/**
 * 카드가 보조기술에 전하는 설명 전체. 여러 카드에 같은 사유가 걸리면 문장은 목록 아래 한 곳에 있고 카드는 번호만
 * 보이므로, 사유가 전해지는지는 카드 안 글이 아니라 `aria-describedby`가 가리키는 글로 본다.
 */
function descriptionOf(card: HTMLElement): string {
  return (card.getAttribute("aria-describedby") ?? "")
    .split(" ")
    .filter(Boolean)
    .map((id) => document.getElementById(id)?.textContent ?? "")
    .join(" ");
}


beforeEach(() => {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loadedCatalog);
  apiMocks.fetchStrategyRegistry.mockResolvedValue(registryFixture);
  storeSelection();
});

afterEach(() => {
  vi.clearAllMocks();
  window.sessionStorage.clear();
});

describe("StrategySettingsPage", () => {
  it("접수되면 그 실행의 결과 주소로 옮긴다", async () => {
    // 005 FR-019. 주소가 되는 것은 접수 응답의 공개 토큰이다 — 화면은 `backtestId`로 주소를 짓지 않는다.
    const user = userEvent.setup();
    const publicToken = "0123456789abcdef0123456789abcdef";
    const { support } = makeSupport({
      createBacktest: async () => ({
        backtestId: "BT-20260916-000001",
        status: "QUEUED" as const,
        publicToken,
      }),
    });
    renderPageWithResultRoute(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    await waitFor(() =>
      expect(screen.getByTestId("result-location").textContent).toBe(
        `/results/${publicToken}`,
      ),
    );
  });

  it("접수 응답에 결과 주소가 없으면 옮기지 않고 요청 번호를 남긴다", async () => {
    // 공개 토큰은 서버가 주는 값이라 없을 때 화면이 지어낼 수 없다. 접수된 요청을 잃지 않게 번호를 보인다.
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPageWithResultRoute(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    await waitFor(() =>
      expect(
        screen
          .getAllByRole("status")
          .some((node) => node.textContent?.includes("BT-20260916-000001")),
      ).toBe(true),
    );
    expect(screen.queryByTestId("result-location")).toBeNull();
  });

  it("카드 한 번과 분석하기 한 번으로 기본값 요청이 만들어진다", async () => {
    // SC-001, quickstart 4. 전략 설정을 한 번도 열지 않는다.
    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    await waitFor(() => expect(requests).toHaveLength(1));

    const request = requests[0];
    expect(request.executionType).toBe("SINGLE");
    expect(request.strategy.strategyId).toBe(selectableStrategy.strategyId);
    expect(request.strategy.registryVersion).toBe(
      registryFixture.registryVersion,
    );
    expect(request.strategy.rulesVersion).toBe(RULES_VERSION);
    // 비용 버전은 화면에 보인 기본 비용 조건의 버전을 명시해 싣는다(004 결정, T105).
    expect(request.conditions.costProfileVersion).toBe(
      versionsFixture.costProfileVersion,
    );

    // 등록표의 기본값 그대로다. 사용자는 값을 하나도 입력하지 않았다.
    for (const axis of selectableStrategy.axes) {
      expect(request.strategy.parameters[axis.name]).toEqual(axis.defaultValue);
    }

    // 워밍업은 규칙으로 계산한 값이며 카드의 기본 설정 워밍업과 같다.
    expect(request.conditions.warmupTradingDays).toBe(
      selectableStrategy.warmupTradingDays,
    );
    expect(request.conditions.start).toBe(windowFixture.start);
    expect(request.conditions.end).toBe(windowFixture.end);
    expect(request.conditions.scenario).toBe("FULL_PERIOD");
    // 금액은 등록표의 기본값을 decimal 문자열로 싣는다.
    expect(request.conditions.initialCash).toBe(
      registryFixture.analysisConditions.initialAmount.default.toFixed(2),
    );

    // 자동 배분 전략이므로 목표 비중을 싣지 않는다(계약 §2, targetWeightSource).
    expect(request.sectorAllocation.mappingVersion).toBe("SEM-TEST-1");
    expect(request.sectorAllocation.items).toHaveLength(3);
    for (const item of request.sectorAllocation.items) {
      expect(item).not.toHaveProperty("targetWeight");
    }

    expect(await screen.findByText(/BT-20260916-000001/)).toBeTruthy();
  });

  it("사용자가 정한 비중을 쓰는 전략에만 목표 비중을 싣는다", async () => {
    const user = userEvent.setup();
    const userWeighted = selectableUserWeighted();
    // 그 전략만 고를 수 있게 둔 응답이다. 화면이 전략 ID로 분기하지 않는다는 것을 본다.
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      strategies: [userWeighted],
    });
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(userWeighted.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    await waitFor(() => expect(requests).toHaveLength(1));
    const request = requests[0];
    for (const item of request.sectorAllocation.items) {
      expect(item.targetWeight).toMatch(/^\d+\.\d{4}$/);
    }
    const total = request.sectorAllocation.items.reduce(
      (sum, item) => sum + Number(item.targetWeight),
      0,
    );
    expect(total).toBeCloseTo(1, 4);
  });

  it("사용자 비중을 쓰는 전략은 비중 합계가 100%가 아니면 분석하기가 막히고 그 자리에서 맞출 수 있다", async () => {
    // T091. 002는 합계가 어긋난 상태도 정상으로 보관하고, 004는 목표 비중 합을 1로 요구한다.
    // 그 사이를 잇는 이 화면이 막지 않으면 서버가 400으로 거절하고 사용자는 무엇을 고칠지 모른다.
    storeUnbalancedSelection();
    const user = userEvent.setup();
    const userWeighted = selectableUserWeighted();
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      strategies: [userWeighted],
    });
    const { support, requests } = makeSupport();
    renderPageWithSectorRoute(support);

    await user.click(await findCardNamed(userWeighted.name));

    const analyze = actionButton("분석하기");
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("true"),
    );
    const reasonId = analyze.getAttribute("aria-describedby");
    const reason = document.getElementById(reasonId!)?.textContent ?? "";
    expect(reason).toContain("합계가 100%가 아닙니다");
    // 얼마나 어긋났는지 002 화면과 같은 형식으로 보인다.
    expect(reason).toContain("99.9%");

    await user.click(analyze);
    expect(requests).toHaveLength(0);

    // 고칠 자리가 같은 화면 안에 있다. 누르면 고른 대상 전체가 같은 비중으로 되돌아간다.
    await user.click(screen.getByRole("button", { name: "동일하게 나누기" }));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
  });

  it("비중 합계가 어긋나도 자동 배분 전략은 막지 않는다", async () => {
    // T091. 자동 배분 전략은 이 비중을 읽지 않는다. 합계 때문에 막으면 필요 이상으로 넓게 막는다.
    storeUnbalancedSelection();
    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    // 픽스처의 이 전략은 등록표가 자동 배분(`COMPUTED`)이라고 말한다.
    expect(selectableStrategy.targetWeightSource).not.toBe("USER");
    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    expect(
      screen.queryByRole("button", { name: "섹터·비중 단계로 이동" }),
    ).toBeNull();

    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    for (const item of requests[0].sectorAllocation.items) {
      expect(item).not.toHaveProperty("targetWeight");
    }
  });

  it("시작 금액 행에 보인 금액이 그대로 요청에 실린다", async () => {
    // T092, quickstart 4. 사용자가 보지 못한 금액으로 분석이 접수되지 않는다.
    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));

    const amount = registryFixture.analysisConditions.initialAmount;
    const summary = screen.getByRole("region", { name: "분석 조건" });
    expect(summary.textContent).toContain(
      `${amount.default.toLocaleString("ko-KR")}${amount.unit ?? ""}`,
    );

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.initialCash).toBe(amount.default.toFixed(2));
  });

  it("변동성 제한을 켜면 그 행에 보이고 요청에 목표 변동성을 싣고 워밍업은 서버에 맡긴다", async () => {
    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await openSetting(user, "변동성 제한");
    await user.click(screen.getByRole("radio", { name: "씀" }));
    const volatility = registryFixture.analysisConditions.targetVolatility;
    await user.selectOptions(
      screen.getByRole("combobox", { name: "목표 변동성(연)" }),
      String(volatility.max),
    );
    expect(
      (
        screen.getByRole("combobox", {
          name: "목표 변동성(연)",
        }) as HTMLSelectElement
      ).value,
    ).toBe(String(volatility.max));

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions).toMatchObject({
      scenario: "TARGET_RISK",
      scenarioParams: { targetVolatility: (volatility.max / 100).toFixed(4) },
      warmupTradingDays: null,
    });
  });

  it("기본 화면에는 짧은 선택만 바로 보이고 날짜·금액·숫자 입력칸은 눌러야 열린다", async () => {
    // SC-005(2026-09-30 개정), T046·T102. 후보가 몇 개뿐인 선택은 알약으로 바로 두고, 범위 안내가 따라야 하는
    // 입력은 그 행을 눌러야 연다.
    const user = userEvent.setup();
    const { support } = makeSupport();
    const { container } = renderPage(support);
    const radioFieldsets = () =>
      Array.from(container.querySelectorAll("fieldset")).filter((fieldset) =>
        fieldset.querySelector('input[type="radio"]'),
      );

    // 고르기 전에는 분석 조건의 짧은 선택 둘만 바로 보인다.
    await findCardNamed(selectableStrategy.name);
    await waitFor(() =>
      expect(radioFieldsets().map((fieldset) => fieldset.getAttribute("aria-label")).sort()).toEqual(
        ["최소 평가 기간", "변동성 제한"].sort(),
      ),
    );

    await user.click(await findCardNamed(selectableStrategy.name));

    expect(
      container.querySelectorAll('input:not([type="radio"]), select, textarea'),
    ).toHaveLength(0);

    // 바로 보이는 선택지 묶음은 정확히 이것들이다: 최소 평가 기간, 변동성 제한, 후보가 넷 이하인 목록형 축.
    // 라디오를 빼고 세는 위 검사가 새로 생긴 선택지 묶음을 놓치지 않게 이름으로 못 박는다.
    const expectedGroups = [
      "최소 평가 기간",
      "변동성 제한",
      ...selectableStrategy.axes
        .filter((axis) => axis.inputType === "CHOICE" && (axis.choices ?? []).length <= 4)
        .map((axis) => axis.label),
    ];
    const radioGroups = radioFieldsets().map((fieldset) => fieldset.getAttribute("aria-label"));
    expect(radioGroups.sort()).toEqual([...expectedGroups].sort());
    // 바로 보이는 선택지 묶음은 어느 것도 후보가 넷을 넘지 않는다.
    for (const fieldset of radioFieldsets()) {
      expect(fieldset.querySelectorAll('input[type="radio"]').length).toBeLessThanOrEqual(4);
    }

    for (const axis of selectableStrategy.axes) {
      if (axis.inputType !== "CHOICE") {
        expect(rowToggle(axis.label)?.getAttribute("aria-expanded")).toBe("false");
      }
    }
  });

  it("상세 설정 카드는 제목이 고정이고, 두 열 위에 기본값 안내와 자동 배분 설명을 둔다", async () => {
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPage(support);

    // 고르기 전에도 제목과 기본값 안내(FR-004)가 있고, 전략 설정 자리는 무엇을 기다리는지 말한다.
    expect(await screen.findByRole("heading", { name: "상세 설정" })).toBeTruthy();
    expect(
      await screen.findByText(/편의용 출발점이며 최적값이나 투자 권고가 아닙니다/),
    ).toBeTruthy();
    expect(screen.getByText("위에서 전략을 고르면 여기에 전략 설정이 나타납니다.")).toBeTruthy();

    // 자동 배분 전략을 고르면 출력 비중이 계산 결과라는 설명(FR-012)이 붙고, 제목은 그대로다.
    await user.click(await findCardNamed(selectableStrategy.name));
    expect(screen.getByRole("region", { name: "상세 설정" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "상세 설정" })).toBeTruthy();
    // 전략 이름은 제목이 아니라 제목 옆 글이다.
    expect(screen.queryByRole("heading", { name: selectableStrategy.name })).toBeNull();
    expect(screen.getByText(selectableStrategy.name, { selector: "strong" })).toBeTruthy();
    expect(
      screen.getByText("출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다."),
    ).toBeTruthy();

    // 동일 비중은 계산으로 비중을 내지 않으므로 그 설명이 없다. 전략을 바꿔도 제목은 그대로다.
    await user.click(await findCardNamed(plainStrategy.name));
    expect(screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/)).toBeNull();
    expect(screen.getByRole("heading", { name: "상세 설정" })).toBeTruthy();
    expect(screen.getByText(plainStrategy.name, { selector: "strong" })).toBeTruthy();
  });

  it("분석하기 원은 고르기 전 숨고, 막히면 절반, 누를 수 있으면 다 드러나며, 이유는 원 옆 문장이 말한다", async () => {
    const user = userEvent.setup();
    const { support } = makeSupport({ fetchAnalysisVersions: async () => null });
    renderPage(support);

    const orb = () => document.querySelector<HTMLButtonElement>('[data-action="analyze"]')!;
    await findCardNamed(selectableStrategy.name);
    expect(orb().getAttribute("data-stage")).toBe("0");
    // 설정 준비가 끝나기 전 한 차례는 "불러오기 전" 문장이다. 자리 잡은 문장을 기다린다.
    await waitFor(() => expect(reasonOf(orb())).toContain("전략 카드를 먼저"));

    // 분석 조건 버전을 받지 못해 막힌다. 고른 뒤라 절반만 나온다. 조회 중에는 "확인하는 중"이 먼저 온다.
    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() => expect(orb().getAttribute("data-stage")).toBe("1"));
    expect(orb().getAttribute("aria-disabled")).toBe("true");
    await waitFor(() => expect(reasonOf(orb())).toContain("분석 조건 버전"));
    // 이유 문장은 원 바로 왼쪽, 상세 설정 카드의 머리 줄에 있다. 카드 아래 멀리 두지 않는다.
    const reasonId = orb().getAttribute("aria-describedby")!;
    const reason = document.getElementById(reasonId)!;
    const detail = screen.getByRole("region", { name: "상세 설정" });
    expect(
      within(detail).getByRole("heading", { name: "상세 설정" }).parentElement?.contains(reason),
    ).toBe(true);
    // 원 칸 안의 사본(좁은 화면용)은 같은 문장이지만 보조기술에 숨는다. 원이 가리키는 문장은 하나다.
    const copy = orb().parentElement?.querySelector("p");
    expect(copy?.textContent).toBe(reason.textContent);
    expect(copy?.getAttribute("aria-hidden")).toBe("true");
  });

  it("앞 단계로 돌아가는 길이 제목 위에 있고 섹터 선택을 가리킨다", async () => {
    // 행동 버튼 줄이 아니라 제목 쪽이다 — 이 화면의 주된 행동은 '분석하기' 하나다(FR-002, 원칙 II).
    const { support } = makeSupport();
    renderPage(support);

    const back = await screen.findByRole("link", { name: /섹터·비중 고르기/ });
    expect(back.getAttribute("href")).toBe("/diagnosis/sectors");
    expect(
      back.compareDocumentPosition(
        screen.getByRole("heading", { name: "전략 선택" }),
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("등록표가 임시값이면 그 사실을 문구로 보인다", async () => {
    // NFR-002. 색만으로 구분하지 않는다.
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));

    expect(screen.getAllByText("임시값").length).toBeGreaterThan(0);
    expect(screen.getByText(/팀 확정 전 임시값입니다/)).toBeTruthy();
    // 비용 조건도 임시 버전이 그대로 보인다(FR-002·FR-010).
    expect(
      screen.getByText(new RegExp(versionsFixture.costProfileVersion)),
    ).toBeTruthy();
  });

  it("등록표 조회가 실패하면 두 행동 버튼이 이유와 함께 막힌다", async () => {
    // SC-008, quickstart 9. 임시 값이나 예비 등록표로 대신 동작하지 않는다.
    apiMocks.fetchStrategyRegistry.mockRejectedValue(
      new Error("registry down"),
    );
    const { support, requests } = makeSupport();
    const user = userEvent.setup();
    renderPage(support);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeTruthy();

    const analyze = actionButton("분석하기");
    const autoCompare = actionButton("설정 조합 자동 비교");
    expect(analyze.getAttribute("aria-disabled")).toBe("true");
    expect(autoCompare.getAttribute("aria-disabled")).toBe("true");

    // 이유가 함께 전달된다 — `disabled`로 막으면 키보드 사용자가 읽을 수 없다(NFR-001).
    const reasonId = analyze.getAttribute("aria-describedby");
    expect(reasonId).toBeTruthy();
    expect(document.getElementById(reasonId!)?.textContent).toContain(
      "불러오기 전에는",
    );

    await user.click(analyze);
    expect(requests).toHaveLength(0);
  });

  it("분석 조건 조회 실패를 재시도하고 성공해도 사용자가 누르기 전에는 분석하지 않는다", async () => {
    let finishRetry!: (value: AnalysisVersions | null) => void;
    const fetchAnalysisVersions = vi
      .fn<AnalysisSupport["fetchAnalysisVersions"]>()
      .mockRejectedValueOnce(new Error("일시적인 조회 실패"))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishRetry = resolve;
          }),
      )
      .mockResolvedValueOnce(versionsFixture);
    const { support, requests } = makeSupport({ fetchAnalysisVersions });
    const user = userEvent.setup();
    renderPage(support);
    await user.click(await findCardNamed(selectableStrategy.name));
    const retry = await screen.findByRole("button", {
      name: "분석 조건 다시 불러오기",
    });
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe("true");
    expect(
      actionButton("설정 조합 자동 비교").getAttribute("aria-disabled"),
    ).toBe("false");
    await user.click(retry);
    expect((retry as HTMLButtonElement).disabled).toBe(true);
    expect(reasonOf(actionButton("분석하기"))).toContain("확인하는 중");
    await user.click(retry);
    expect(fetchAnalysisVersions).toHaveBeenCalledTimes(2);
    await act(async () => {
      finishRetry(null);
    });
    await waitFor(() =>
      expect((retry as HTMLButtonElement).disabled).toBe(false),
    );
    expect(document.activeElement).toBe(retry);
    expect(requests).toHaveLength(0);
    await user.click(retry);
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    expect(
      screen.queryByRole("button", { name: "분석 조건 다시 불러오기" }),
    ).toBeNull();
    expect(document.activeElement).toBe(
      screen.getByRole("region", { name: "전략" }),
    );
    expect(requests).toHaveLength(0);
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.costProfileVersion).toBe(
      versionsFixture.costProfileVersion,
    );
  });

  it("분석 조건 버전을 확인할 수 없으면 분석하기가 막힌다", async () => {
    // "착수 전에 알아둘 것" ②. 값을 지어내 보내지 않는다.
    const user = userEvent.setup();
    const { support, requests } = makeSupport({
      fetchAnalysisVersions: async () => null,
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));

    const analyze = actionButton("분석하기");
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("true"),
    );
    const reasonId = analyze.getAttribute("aria-describedby");
    expect(document.getElementById(reasonId!)?.textContent).toContain(
      "분석 조건 버전",
    );

    await user.click(analyze);
    expect(requests).toHaveLength(0);
  });

  describe("라우트가 쓰는 기본 통로(T107·T110)", () => {
    /** 기본 설정 워밍업 45일이면 줄어들기만 하고, 최소 평가 기간 1년을 채우는 합성 응답 */
    function defaultWindow() {
      const days = Array.from({ length: 91 }, (_, index) =>
        new Date(Date.UTC(2024, 0, 1 + index)).toISOString().slice(0, 10),
      );
      return {
        commonStart: days[0],
        commonEnd: "2026-01-05",
        warmupTradingDays: 0,
        bindingCandidate: "BETA.XKRX",
        bindingSector: "BETA",
        excludedRanges: [],
        evaluable: true,
        datasetId: "TEST-DATASET-1",
        warmupBaseDate: days[0],
        tradingDaysFromBase: days,
        maxWarmupTradingDays: 90,
        excludedRangesFromBase: [],
      };
    }

    it("평가 기간과 004 registry를 한 번씩 받아 카드를 판정하고, registry의 버전을 실어 보낸다", async () => {
      const xhrSpy = vi.spyOn(XMLHttpRequest.prototype, "open");
      try {
        const user = userEvent.setup();
        apiMocks.fetchEvaluationWindow.mockResolvedValue(defaultWindow());
        backtestMocks.fetchAnalysisVersions.mockResolvedValue(versionsFixture);
        backtestMocks.createBacktest.mockResolvedValue({
          backtestId: "BT-20260917-000009",
          status: "QUEUED",
        });
        renderPage();

        const card = await findCardNamed(selectableStrategy.name);
        await waitFor(() =>
          expect(card.getAttribute("data-warmup")).toBe("reduced"),
        );
        expect(apiMocks.fetchEvaluationWindow).toHaveBeenCalledTimes(1);
        expect(apiMocks.fetchEvaluationWindow.mock.calls[0][0]).toEqual({
          sectors: ["ALPHA", "BETA", "GAMMA"],
          maxWarmupTradingDays: 90,
        });

        await user.click(card);
        await waitFor(() =>
          expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
            "false",
          ),
        );
        await user.click(actionButton("분석하기"));

        await waitFor(() =>
          expect(backtestMocks.createBacktest).toHaveBeenCalledTimes(1),
        );
        const request = backtestMocks.createBacktest.mock
          .calls[0][0] as BacktestCreateRequest;
        expect(request.strategy.rulesVersion).toBe(RULES_VERSION);
        expect(request.conditions.costProfileVersion).toBe(
          versionsFixture.costProfileVersion,
        );
        expect(backtestMocks.fetchAnalysisVersions).toHaveBeenCalledTimes(1);
        // 조회·전송은 모두 대역이라 실제 네트워크로는 아무것도 나가지 않는다.
        expect(xhrSpy).not.toHaveBeenCalled();
      } finally {
        xhrSpy.mockRestore();
      }
    });

    it("공통 평가 기간을 받지 못하면 카드가 판정하지 못했다는 사실을 보이고 분석은 막힌다", async () => {
      apiMocks.fetchEvaluationWindow.mockRejectedValue(new Error("404"));
      backtestMocks.fetchAnalysisVersions.mockResolvedValue(versionsFixture);
      renderPage();

      await findCardNamed(selectableStrategy.name);

      await waitFor(() =>
        expect(
          screen.getAllByText(/공통 평가 기간을 확인할 수 없어/).length,
        ).toBeGreaterThan(0),
      );
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );
    });

    it("평가 기간을 받지 못했다가 다시 불러오면 그 응답으로 카드를 판정한다", async () => {
      // 409 EVALUATION_WINDOW_NOT_READY처럼 곧 풀리는 실패는 새로 고치지 않고도 다시 불러올 수 있어야 한다.
      const user = userEvent.setup();
      apiMocks.fetchEvaluationWindow
        .mockRejectedValueOnce(new Error("409 EVALUATION_WINDOW_NOT_READY"))
        .mockResolvedValueOnce(defaultWindow());
      backtestMocks.fetchAnalysisVersions.mockResolvedValue(versionsFixture);
      renderPage();

      const retry = await screen.findByRole("button", {
        name: "평가 기간 다시 불러오기",
      });
      expect(
        (await findCardNamed(selectableStrategy.name)).getAttribute(
          "data-warmup",
        ),
      ).toBe("unknown");

      await user.click(retry);

      await waitFor(async () =>
        expect(
          (await findCardNamed(selectableStrategy.name)).getAttribute(
            "data-warmup",
          ),
        ).toBe("reduced"),
      );
      expect(apiMocks.fetchEvaluationWindow).toHaveBeenCalledTimes(2);
      expect(
        screen.queryByRole("button", { name: "평가 기간 다시 불러오기" }),
      ).toBeNull();
    });
  });

  it("워밍업을 채우지 못하는 전략은 고를 수 없고 분석도 막힌다", async () => {
    // FR-011. 카드가 막히면 '분석하기'도 그 이유로 막힌다.
    const user = userEvent.setup();
    const { support, requests } = makeSupport({
      judgeWarmup: () => ({
        verdict: {
          state: "blocked",
          message: "최소 평가 기간(3년)에 못 미칩니다.",
        },
        window: null,
        reduction: null,
      }),
    });
    renderPage(support);

    const card = await findCardNamed(selectableStrategy.name);
    expect(card.getAttribute("aria-disabled")).toBe("true");
    expect(descriptionOf(card)).toMatch(/최소 평가 기간\(3년\)에 못 미칩니다/);

    await user.click(card);
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe("true");
    expect(requests).toHaveLength(0);
  });

  it("앞 단계를 마치지 않았으면 카드를 그리지 않고 되돌린다", async () => {
    // spec Edge Cases. 섹터가 두 개 미만이면 기본값을 만들지 않는다.
    storeSelection(["ALPHA"]);
    const { support } = makeSupport();
    renderPage(support);

    expect(
      await screen.findByText(/섹터·비중 단계를 먼저 마쳐 주세요/),
    ).toBeTruthy();
    expect(
      screen.queryByRole("button", {
        name: (accessibleName) =>
          accessibleName.includes(selectableStrategy.name),
      }),
    ).toBeNull();
    // 앞 단계를 확인하기 전에는 등록표를 조회하지도 않는다.
    expect(apiMocks.fetchStrategyRegistry).not.toHaveBeenCalled();
  });

  it("등록표를 섹터 수와 함께 조회한다", async () => {
    const { support } = makeSupport();
    renderPage(support);

    await waitFor(() =>
      expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalled(),
    );
    expect(apiMocks.fetchStrategyRegistry.mock.calls[0][0]).toBe(
      catalogSectors.length,
    );
  });

  it("전략을 고르기 전에는 분석하기가 그 이유로 막힌다", async () => {
    const { support } = makeSupport();
    renderPage(support);

    await findCardNamed(selectableStrategy.name);
    const analyze = actionButton("분석하기");
    expect(analyze.getAttribute("aria-disabled")).toBe("true");
    // 카드는 등록표를 받자마자 그려지지만 보관값 재검증은 그다음 렌더에 끝난다. 그 사이의 이유는
    // '불러오기 전'이라, 곧바로 읽으면 실행마다 결과가 달라진다.
    await waitFor(() =>
      expect(reasonOf(analyze)).toContain("전략 카드를 먼저"),
    );
  });

  it("보관값이 현재 등록표에서 유효하지 않으면 알리고 분석을 막는다", async () => {
    // FR-017. 자동 보정하지 않고 복원 선택을 보인다.
    const numberAxis = selectableStrategy.axes.find(
      (axis) => axis.inputType === "NUMBER",
    )!;
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: "OLD-REGISTRY",
        universeSize: 3,
        selectedStrategyId: selectableStrategy.strategyId,
        valuesByStrategy: {
          [selectableStrategy.strategyId]: { [numberAxis.name]: 999 },
        },
        analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
        invalidValues: [],
      }),
    );

    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    expect(
      await screen.findByText(new RegExp(`${numberAxis.label}.*999`)),
    ).toBeTruthy();
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe("true");

    await user.click(screen.getByRole("button", { name: "기본값 복원" }));

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    const request = requests[0];
    expect(request.strategy.parameters[numberAxis.name]).toEqual(
      numberAxis.defaultValue,
    );
  });

  it("요청을 보내는 동안 다시 누를 수 없다", async () => {
    const user = userEvent.setup();
    // 응답을 붙들어 둔 채 '보내는 중' 상태를 만든다. 해제 함수를 홀더에 담는 까닭은 프로미스 안에서
    // 대입한 값을 바깥에서 그대로 읽기 위해서다.
    const pending: { release: () => void } = { release: () => {} };
    const calls: BacktestCreateRequest[] = [];
    const { support } = makeSupport({
      createBacktest: (request) => {
        calls.push(request);
        return new Promise((resolve) => {
          pending.release = () =>
            resolve({ backtestId: "BT-1", status: "QUEUED" });
        });
      },
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );

    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(calls).toHaveLength(1));

    // 보내는 중에는 같은 버튼이 다른 이름으로 바뀌고 다시 눌리지 않는다. 조건이 빠진 것이 아니라 원은 다 드러난
    // 채로 두고, 보이는 글자도 바뀐다.
    const busy = actionButton("분석 요청을 보내는 중");
    expect(busy.getAttribute("aria-disabled")).toBe("true");
    expect(busy.getAttribute("aria-busy")).toBe("true");
    expect(busy.getAttribute("data-stage")).toBe("2");
    expect(busy.textContent).toContain("보내는 중");
    await user.click(busy);
    expect(calls).toHaveLength(1);

    pending.release();
  });

  it("전략과 무관한 보관값의 무효 안내는 전략을 고르기 전에 보인다", async () => {
    // T093, FR-017. 최소 평가 기간은 카드의 선택 가능 여부를 바꾸는 값이라(FR-011a) 카드를 보기
    // 전에 알아야 한다. 전략 설정 안내에 묶어 두면 고른 뒤에야 알게 된다.
    const condition = registryFixture.analysisConditions.minEvaluationYears;
    const notAChoice =
      Math.max(...condition.choices.map((choice) => Number(choice.value))) + 99;
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: registryFixture.registryVersion,
        universeSize: catalogSectors.length,
        selectedStrategyId: null,
        valuesByStrategy: {},
        analysisCondition: {
          minEvaluationYears: notAChoice,
          scenario: "FULL_PERIOD",
        },
        invalidValues: [],
      }),
    );

    const { support } = makeSupport();
    renderPage(support);

    // 전략을 하나도 고르지 않은 상태에서 보인다.
    const notice = await screen.findByRole("status", {
      name: "다시 확인할 분석 조건",
    });
    expect(notice.textContent).toContain(String(notAChoice));
    expect(
      screen.queryByRole("status", { name: "다시 확인할 설정값" }),
    ).toBeNull();
  });

  it("부모가 같은 조회 함수를 담아 통로를 새로 펼쳐도 버전을 다시 조회하지 않는다", async () => {
    // T094. 통로 객체의 참조가 아니라 조회 함수의 참조를 따른다. 호출자가 렌더마다 `{ ...base }`로
    // 새 객체를 만들어도 담긴 함수가 같으면 요청은 한 번이다.
    let fetchCount = 0;
    const { support: base } = makeSupport();
    const stableFetch: AnalysisSupport["fetchAnalysisVersions"] = async () => {
      fetchCount += 1;
      return versionsFixture;
    };

    renderHost(() => ({ ...base, fetchAnalysisVersions: stableFetch }));

    const user = userEvent.setup();
    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );

    await user.click(screen.getByRole("button", { name: "부모 다시 그리기" }));
    await user.click(screen.getByRole("button", { name: "부모 다시 그리기" }));

    expect(fetchCount).toBe(1);
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
      "false",
    );
  });

  it("다시 조회하는 동안 받은 버전을 거두지 않아 분석하기가 깜빡이지 않는다", async () => {
    // T094가 막으려던 사용자 쪽 결함은 요청 횟수가 아니라, 다시 조회하는 동안 '확인하는 중'으로
    // 돌아가 '분석하기'가 잠깐씩 막히는 것이다. **두 번째 조회를 끝나지 않게 붙들어** 그 틈을 검사가
    // 반드시 보게 한다. 되돌아간다면 이 틈에서 버튼이 막혀 있다.
    let fetchCount = 0;
    const { support: base } = makeSupport();

    renderHost(() => ({
      ...base,
      // 일부러 렌더마다 새 함수를 만든다. 첫 조회만 끝나고 뒤의 조회는 끝나지 않는다.
      fetchAnalysisVersions: () => {
        fetchCount += 1;
        return fetchCount === 1
          ? Promise.resolve(versionsFixture)
          : new Promise<never>(() => {});
      },
    }));

    const user = userEvent.setup();
    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );

    await user.click(screen.getByRole("button", { name: "부모 다시 그리기" }));

    // 조회는 다시 나갔고(함수가 바뀌었으므로) 아직 끝나지 않았다. 그래도 버튼은 막히지 않는다.
    expect(fetchCount).toBeGreaterThan(1);
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
      "false",
    );
  });

  it("통로를 실제로 바꾸면 버전 조회도 새 통로를 따른다", async () => {
    // S15P21E205-274. 화면이 열릴 때 한 번만 조회하던 때는 연결 전 통로의 `null`에 머물러, 새 통로로
    // 바꿔도 '분석하기'가 "분석 조건 버전을 확인할 수 없습니다"로 계속 막혔다.
    const { support: connected } = makeSupport();
    const disconnected: AnalysisSupport = {
      ...connected,
      fetchAnalysisVersions: async () => null,
    };

    renderHost((generation) => (generation === 0 ? disconnected : connected));

    const user = userEvent.setup();
    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() => {
      const reasonId =
        actionButton("분석하기").getAttribute("aria-describedby");
      expect(document.getElementById(reasonId!)?.textContent).toContain(
        "분석 조건 버전",
      );
    });

    // 부모가 연결된 통로로 바꾼다.
    await user.click(screen.getByRole("button", { name: "부모 다시 그리기" }));

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
  });

  it("보관된 시작 금액이 등록표 범위 밖이면 그 금액을 보이지도 보내지도 않고 복원을 기다린다", async () => {
    // S15P21E205-274, SC-002·FR-017. 재검증이 분석 조건을 통째로 옮겨 범위 밖 금액이 살아남던 결함이다.
    // 비운 금액이 곧 기본값이 되면 조용히 바뀐 것과 같으므로, 복원을 누르기 전까지 금액을 모른다.
    const amount = registryFixture.analysisConditions.initialAmount;
    const outOfRange = amount.min - amount.step;
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: registryFixture.registryVersion,
        universeSize: catalogSectors.length,
        selectedStrategyId: null,
        valuesByStrategy: {},
        analysisCondition: {
          minEvaluationYears:
            registryFixture.analysisConditions.minEvaluationYears.default,
          scenario: "FULL_PERIOD",
          initialAmount: outOfRange,
        },
        invalidValues: [],
      }),
    );

    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    // 전략을 고르기 전에도 안내가 보인다(T093과 같은 자리).
    const notice = await screen.findByRole("status", {
      name: "다시 확인할 분석 조건",
    });
    expect(notice.textContent).toContain(String(outOfRange));

    await user.click(await findCardNamed(selectableStrategy.name));

    // 기본값으로 대신 보이지 않는다.
    const summary = screen.getByRole("region", { name: "분석 조건" });
    expect(summary.textContent).not.toContain(
      amount.default.toLocaleString("ko-KR"),
    );
    expect(summary.textContent).toContain("확인할 수 없음");

    const analyze = actionButton("분석하기");
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("true"),
    );
    await user.click(analyze);
    expect(requests).toHaveLength(0);

    // 사용자가 복원을 누르면 그제야 기본값이 쓰인다.
    await user.click(
      within(notice).getByRole("button", { name: "기본값 복원" }),
    );
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.initialCash).toBe(amount.default.toFixed(2));
  });

  it("요청을 조립하다 실패하면 실패 안내를 보인다", async () => {
    // S15P21E205-274. 조립 예외가 `try` 밖이라 거부된 프로미스로 버려져, 버튼을 눌러도 아무 일도
    // 일어나지 않았다. 계약을 어긴 등록표(기본 시작 금액 0)로 조립이 반드시 실패하게 만든다.
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      analysisConditions: {
        ...registryFixture.analysisConditions,
        initialAmount: {
          ...registryFixture.analysisConditions.initialAmount,
          default: 0,
        },
      },
    });

    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("분석 요청을 만들지 못했습니다");
    // 내부 예외 문장을 사용자에게 그대로 보이지 않는다.
    expect(alert.textContent).not.toContain("RangeError");
    expect(requests).toHaveLength(0);
    // 원인은 개발 도구에 남긴다.
    expect(consoleError).toHaveBeenCalled();
    // 이 파일의 afterEach는 모의를 지우기만 하고 되돌리지 않는다. 되돌리지 않으면 뒤의 검사에서
    // 오류가 조용히 삼켜진다.
    consoleError.mockRestore();
  });

  it("선택을 만든 뒤 섹터 대응표가 바뀌었으면 요청을 만들지 않고 앞 단계로 안내한다", async () => {
    // S15P21E205-274, 원칙 VIII. 종목은 지금 목록에서, 버전은 보관값에서 오면 서로 다른 대응표가
    // 한 요청에 섞인다. 서버는 요청의 버전으로 (섹터, 종목) 쌍을 검증한다.
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: "SEM-TEST-0",
        selected: [
          { code: "ALPHA", weightTenths: 500 },
          { code: "BETA", weightTenths: 500 },
        ],
        weightsTouched: true,
      }),
    );

    const { support } = makeSupport();
    renderPage(support);

    expect(await screen.findByText(/섹터 대응표가 바뀌었습니다/)).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "섹터·비중 단계로 이동" }),
    ).toBeTruthy();
    // 앞 단계를 확인하기 전에는 카드도 등록표 조회도 없다.
    expect(
      screen.queryByRole("button", {
        name: (accessibleName) =>
          accessibleName.includes(selectableStrategy.name),
      }),
    ).toBeNull();
    expect(apiMocks.fetchStrategyRegistry).not.toHaveBeenCalled();
  });

  it("축이 빠진 설정으로는 요청을 보내지 않는다", async () => {
    // T095. 차단 판정을 "복원 안내가 있는가"가 아니라 "축이 다 찼는가"로 묻는다. 축이 빠진 요청은
    // 서버가 400으로 거절한다(T048).
    const numberAxis = selectableStrategy.axes.find(
      (axis) => axis.inputType === "NUMBER",
    )!;
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: "OLD-REGISTRY",
        universeSize: catalogSectors.length,
        selectedStrategyId: selectableStrategy.strategyId,
        valuesByStrategy: {
          [selectableStrategy.strategyId]: { [numberAxis.name]: 9_999 },
        },
        analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
        invalidValues: [],
      }),
    );

    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    const analyze = await screen.findByRole("button", {
      name: (accessibleName) => accessibleName.includes("분석하기"),
    });
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("true"),
    );

    await user.click(analyze);
    expect(requests).toHaveLength(0);

    // 복원하면 축이 다 차고 그제야 보낼 수 있다.
    await user.click(screen.getByRole("button", { name: "기본값 복원" }));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));

    for (const axis of selectableStrategy.axes) {
      expect(requests[0].strategy.parameters[axis.name]).toEqual(
        axis.defaultValue,
      );
    }
  });

  it("전송에 실패하면 그 사실을 알리고 조용히 다시 보내지 않는다", async () => {
    const user = userEvent.setup();
    const calls: BacktestCreateRequest[] = [];
    const { support } = makeSupport({
      createBacktest: async (request) => {
        calls.push(request);
        throw new Error("서버가 요청을 거절했습니다.");
      },
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("서버가 요청을 거절했습니다.");
    // 조용히 다시 보내지 않는다.
    expect(calls).toHaveLength(1);
  });
  describe("설정 조합 자동 비교로 넘어가기(FR-013, quickstart 11)", () => {
    it("고른 전략 하나와 섹터 후보 집합 전부를 007 화면으로 넘긴다", async () => {
      const user = userEvent.setup();
      const { support, requests } = makeSupport();
      renderPageWithParamSearchRoute(support);

      // 다른 전략을 한 번 골랐다가 옮겨도 마지막에 고른 전략 하나만 넘어간다.
      const other = registryFixture.strategies.find(
        (strategy) =>
          strategy.selectable &&
          strategy.strategyId !== selectableStrategy.strategyId,
      )!;
      await user.click(await findCardNamed(other.name));
      await user.click(await findCardNamed(selectableStrategy.name));

      await waitFor(() =>
        expect(
          actionButton("설정 조합 자동 비교").getAttribute("aria-disabled"),
        ).toBe("false"),
      );
      await user.click(actionButton("설정 조합 자동 비교"));

      const location = await screen.findByTestId("param-search-location");
      const url = new URL(location.textContent!, "http://localhost");
      expect(url.pathname).toBe("/param-search");
      expect(url.searchParams.get("strategies")).toBe(
        selectableStrategy.strategyId,
      );
      expect(url.searchParams.get("instruments")?.split(",")).toEqual(
        catalogSectors.map((item) => item.representativeEtf.instrumentId),
      );
      // 질의 문자열은 둘뿐이다. 후보 숫자나 설정값을 싣지 않는다 — 탐색 범위는 007이 정한다.
      expect([...url.searchParams.keys()].sort()).toEqual([
        "instruments",
        "strategies",
      ]);
      // 분석 요청을 대신 보내지 않는다.
      expect(requests).toHaveLength(0);
    });

    it("분석 조건 버전이 없어 분석하기가 막혀도 자동 비교로는 넘어갈 수 있다", async () => {
      // 버전은 '분석하기'의 요청 조립에만 필요하다. 자동 비교의 조건 확인은 007 화면이 한다.
      const user = userEvent.setup();
      renderPageWithParamSearchRoute(
        makeSupport({ fetchAnalysisVersions: async () => null }).support,
      );

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(reasonOf(actionButton("분석하기"))).toContain("분석 조건 버전"),
      );
      expect(
        actionButton("설정 조합 자동 비교").getAttribute("aria-disabled"),
      ).toBe("false");

      await user.click(actionButton("설정 조합 자동 비교"));
      expect(await screen.findByTestId("param-search-location")).toBeTruthy();
    });

    it("전략을 고르기 전에는 자동 비교가 그 이유로 막히고 이동하지 않는다", async () => {
      const user = userEvent.setup();
      renderPageWithParamSearchRoute(makeSupport().support);

      await findCardNamed(selectableStrategy.name);
      // 카드가 먼저 그려지고 보관값 확인이 뒤따르므로 이유가 자리 잡기를 기다린다.
      await waitFor(() =>
        expect(reasonOf(actionButton("설정 조합 자동 비교"))).toContain(
          "전략 카드를 먼저",
        ),
      );
      const autoCompare = actionButton("설정 조합 자동 비교");
      expect(autoCompare.getAttribute("aria-disabled")).toBe("true");

      await user.click(autoCompare);
      expect(screen.queryByTestId("param-search-location")).toBeNull();
    });

    it("등록표를 받지 못했으면 자동 비교가 막히고 이동하지 않는다", async () => {
      apiMocks.fetchStrategyRegistry.mockRejectedValue(
        new Error("registry down"),
      );
      const user = userEvent.setup();
      renderPageWithParamSearchRoute(makeSupport().support);

      expect(await screen.findByRole("alert")).toBeTruthy();
      const autoCompare = actionButton("설정 조합 자동 비교");
      expect(autoCompare.getAttribute("aria-disabled")).toBe("true");
      expect(reasonOf(autoCompare)).toContain("불러오기 전에는");

      await user.click(autoCompare);
      expect(screen.queryByTestId("param-search-location")).toBeNull();
    });

    it("보관된 선택이 지금 고를 수 없는 전략이면 그 이유로 막힌다", async () => {
      window.sessionStorage.setItem(
        STRATEGY_SETTINGS_STORAGE_KEY,
        JSON.stringify({
          version: 1,
          registryVersion: registryFixture.registryVersion,
          universeSize: 3,
          selectedStrategyId: unselectableStrategy.strategyId,
          valuesByStrategy: {},
          analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
          invalidValues: [],
        }),
      );
      const user = userEvent.setup();
      renderPageWithParamSearchRoute(makeSupport().support);

      await findCardNamed(unselectableStrategy.name);
      await waitFor(() =>
        expect(reasonOf(actionButton("설정 조합 자동 비교"))).toContain(
          unselectableStrategy.unselectableReason!,
        ),
      );
      const autoCompare = actionButton("설정 조합 자동 비교");
      expect(autoCompare.getAttribute("aria-disabled")).toBe("true");

      await user.click(autoCompare);
      expect(screen.queryByTestId("param-search-location")).toBeNull();
    });
  });

  describe("바꾼 값을 그 자리에서 판정한다(T058·T059, quickstart 5-1·7·10)", () => {
    /**
     * 워밍업에 따라 판정이 셋으로 갈리는 흉내. 기본 설정(45거래일)은 기간이 줄어드는 것으로, 90거래일은
     * 구간이 사라지는 것으로 둔다. **줄어드는 것과 막히는 것을 구분하는지** 함께 본다(quickstart 7).
     */
    const judgeByWarmup: AnalysisSupport["judgeWarmup"] = ({
      warmupTradingDays,
    }) => {
      if (warmupTradingDays <= 15) {
        return {
          verdict: { state: "ok" },
          window: windowFixture,
          reduction: null,
        };
      }
      if (warmupTradingDays <= 45) {
        return {
          verdict: {
            state: "reduced",
            message: `워밍업 ${warmupTradingDays}거래일만큼 평가 기간이 줄어듭니다.`,
          },
          window: windowFixture,
          reduction: null,
        };
      }
      return {
        verdict: {
          state: "blocked",
          message: `워밍업 ${warmupTradingDays}거래일을 채우면 평가할 구간이 남지 않습니다.`,
        },
        window: null,
        reduction: null,
      };
    };

    /** 서버가 등록표 버전 변경으로 거절한 것처럼 던진다. 화면은 `code`만 본다. */
    function registryVersionChangedError(): Error {
      return Object.assign(new Error("등록표 버전이 바뀌었습니다."), {
        code: "REGISTRY_VERSION_CHANGED",
      });
    }

    /** 버전만 올린 등록표. `patch`로 고른 전략을 바꿀 수 있다. */
    function nextRegistry(
      patch: (strategy: StrategyView) => StrategyView = (strategy) => strategy,
    ) {
      return {
        ...registryFixture,
        registryVersion: "TEST-REGISTRY-003-2",
        strategies: registryFixture.strategies.map((strategy) =>
          strategy.strategyId === selectableStrategy.strategyId
            ? patch(strategy)
            : strategy,
        ),
      };
    }

    it("관측 기간을 늘리면 고른 카드의 워밍업 판정과 분석하기가 그 자리에서 바뀌고 서버에 묻지 않는다", async () => {
      const fetchSpy = vi.spyOn(globalThis, "fetch");
      const xhrSpy = vi.spyOn(XMLHttpRequest.prototype, "open");
      try {
        const user = userEvent.setup();
        const { support, requests } = makeSupport({
          judgeWarmup: judgeByWarmup,
        });
        renderPage(support);

        await user.click(await findCardNamed(selectableStrategy.name));
        await waitFor(() =>
          expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
            "false",
          ),
        );
        // 기본 설정은 줄어든 기간만 알리고 막지 않는다.
        let card = await findCardNamed(selectableStrategy.name);
        expect(card.getAttribute("data-warmup")).toBe("reduced");

        await openSetting(user, "표류 관측 창");
        await user.click(screen.getByRole("radio", { name: "90거래일" }));

        // 고른 카드는 지금 설정으로 다시 판정되어 막히지만 고른 사실은 남는다.
        card = await findCardNamed(selectableStrategy.name);
        expect(card.getAttribute("data-warmup")).toBe("blocked");
        expect(card.getAttribute("aria-disabled")).toBe("true");
        expect(card.getAttribute("aria-checked")).toBe("true");
        expect(descriptionOf(card).match(/워밍업 90거래일을 채우면/)).toBeTruthy();
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "true",
        );
        expect(reasonOf(actionButton("분석하기"))).toContain(
          "워밍업 90거래일을 채우면",
        );

        // 워밍업을 줄이면 다시 풀린다.
        await user.click(screen.getByRole("radio", { name: "15거래일" }));
        card = await findCardNamed(selectableStrategy.name);
        expect(card.getAttribute("data-warmup")).toBe("ok");
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        );

        // 값을 만지는 동안 등록표·섹터 목록을 다시 조회하지도, 다른 요청을 보내지도 않았다.
        expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalledTimes(1);
        expect(apiMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1);
        expect(fetchSpy).not.toHaveBeenCalled();
        expect(xhrSpy).not.toHaveBeenCalled();
        expect(requests).toHaveLength(0);

        await user.click(actionButton("분석하기"));
        await waitFor(() => expect(requests).toHaveLength(1));
        expect(requests[0].strategy.parameters.driftWindow).toBe(15);
        expect(requests[0].conditions.warmupTradingDays).toBe(15);
      } finally {
        fetchSpy.mockRestore();
        xhrSpy.mockRestore();
      }
    });

    it("규칙을 어기는 값으로 바꾸면 즉시 알리고 분석하기를 막으며, 복원하면 풀린다", async () => {
      const user = userEvent.setup();
      const { support, requests } = makeSupport();
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      await openSetting(user, "허용 이탈 폭");

      const band = screen.getByRole("textbox", { name: /허용 이탈 폭/ });
      await user.clear(band);
      await user.type(band, "30");

      // 30 × 섹터 3 = 90 < 100. 범위 안이지만 규칙 위반이며, 등록표 문구가 그대로 보인다.
      const rule = selectableStrategy.validationRules[0];
      expect(screen.getByRole("alert").textContent).toBe(rule.detail);
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "설정 규칙을 어기는 값이 있습니다",
      );

      await user.click(actionButton("분석하기"));
      expect(requests).toHaveLength(0);

      await user.click(
        screen.getByRole("button", { name: "모두 기본값 복원" }),
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      );
    });

    it("등록표 버전이 바뀌어 거절되면 다시 조회해 알리고, 유효한 값은 두며 다시 보내지 않는다", async () => {
      const user = userEvent.setup();
      const calls: BacktestCreateRequest[] = [];
      const { support } = makeSupport({
        createBacktest: async (request) => {
          calls.push(request);
          if (calls.length === 1) throw registryVersionChangedError();
          return { backtestId: "BT-20260917-000002", status: "QUEUED" };
        },
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await openSetting(user, "표류 관측 창");
      await user.click(screen.getByRole("radio", { name: "15거래일" }));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );

      apiMocks.fetchStrategyRegistry.mockResolvedValue(nextRegistry());
      await user.click(actionButton("분석하기"));

      const notice = await screen.findByText(
        /확인하신 사이에 등록표나 분석 규칙이 바뀌었습니다/,
      );
      await waitFor(() =>
        expect(notice.parentElement?.textContent).toContain(
          "바뀐 등록표에서도 쓸 수 있어 그대로 두었습니다",
        ),
      );
      expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalledTimes(2);
      // 실패 안내로 접지 않는다. 등록표가 바뀐 것은 요청이 잘못된 것과 다른 사실이다.
      expect(screen.queryByRole("alert")).toBeNull();

      // 자동으로 다시 보내지 않는다.
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      expect(calls).toHaveLength(1);

      // 사용자가 다시 누르면 바뀐 버전으로, 바꿔 둔 값 그대로 나간다.
      await user.click(actionButton("분석하기"));
      await waitFor(() => expect(calls).toHaveLength(2));
      expect(calls[1].strategy.registryVersion).toBe("TEST-REGISTRY-003-2");
      expect(calls[1].strategy.parameters.driftWindow).toBe(15);
      // 다시 누르면 앞선 알림을 거둔다.
      expect(
        screen.queryByText(/확인하신 사이에 등록표나 분석 규칙이 바뀌었습니다/),
      ).toBeNull();
    });

    it("규칙 버전이 대체되어 409로 거절되면 004 규칙 등록표도 다시 받고, 받기 전에는 다시 보낼 수 없다", async () => {
      // 004 결정: 대체된 옛 규칙 버전을 실은 새 요청은 409 REGISTRY_VERSION_CHANGED다. 003 등록표는 그대로다.
      const user = userEvent.setup();
      const calls: BacktestCreateRequest[] = [];
      const rulesFor = (rulesVersion: string): AnalysisVersions => ({
        ...versionsFixture,
        strategyRules: versionsFixture.strategyRules.map((row) =>
          row.strategyId === selectableStrategy.strategyId
            ? { ...row, rulesVersion }
            : row,
        ),
      });
      let releaseSecond: (versions: AnalysisVersions) => void = () => {};
      const fetchAnalysisVersions =
        vi.fn<AnalysisSupport["fetchAnalysisVersions"]>();
      fetchAnalysisVersions
        .mockResolvedValueOnce(rulesFor("R-1"))
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              releaseSecond = resolve;
            }),
        );
      const { support } = makeSupport({
        fetchAnalysisVersions,
        createBacktest: async (request) => {
          calls.push(request);
          if (calls.length === 1) throw registryVersionChangedError();
          return { backtestId: "BT-20260917-000003", status: "QUEUED" };
        },
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      // 003 등록표는 바뀌지 않았다. 실제 조회처럼 같은 내용의 새 응답을 돌려준다.
      apiMocks.fetchStrategyRegistry.mockResolvedValue({ ...registryFixture });
      await user.click(actionButton("분석하기"));
      await waitFor(() => expect(calls).toHaveLength(1));
      expect(calls[0].strategy.rulesVersion).toBe("R-1");

      // 새 규칙 버전을 받기 전에는 옛 버전으로 다시 보낼 수 없고, 다시 받았다고 말하지도 않는다.
      await waitFor(() =>
        expect(fetchAnalysisVersions).toHaveBeenCalledTimes(2),
      );
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "분석 조건 버전을 확인하는 중입니다",
      );
      const notice = await screen.findByText(
        /확인하신 사이에 등록표나 분석 규칙이 바뀌었습니다/,
      );
      expect(notice.parentElement?.getAttribute("data-phase")).toBe(
        "reloading",
      );
      // 다시 받는 동안 비용 조건을 "확인할 수 없음"으로 말하지 않는다. '분석하기' 사유와 같은 말을 한다.
      const summary = screen.getByRole("region", { name: "분석 조건" });
      expect(summary.textContent).toContain("확인하는 중");
      expect(summary.textContent).not.toContain("확인할 수 없음");
      await user.click(actionButton("분석하기"));
      expect(calls).toHaveLength(1);

      await act(async () => releaseSecond(rulesFor("R-2")));
      await waitFor(() =>
        expect(notice.parentElement?.getAttribute("data-phase")).toBe(
          "reloaded",
        ),
      );
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      // 자동으로 다시 보내지 않는다.
      expect(calls).toHaveLength(1);

      await user.click(actionButton("분석하기"));
      await waitFor(() => expect(calls).toHaveLength(2));
      expect(calls[1].strategy.rulesVersion).toBe("R-2");
    });

    it("409 직후 새 등록표를 받기 전에는 바뀐 등록표로 확인했다고 말하지 않는다", async () => {
      // 코드리뷰 지적. 409 직후 첫 렌더에는 옛 등록표가 아직 '받음' 상태라, 그것을 새 등록표로 읽으면
      // 확인하지도 않은 "그대로 두었습니다"가 잠깐 그려지고 status 영역이라 읽힐 수 있다.
      const user = userEvent.setup();
      const { support } = makeSupport({
        createBacktest: async () => {
          throw registryVersionChangedError();
        },
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );

      // 다시 받는 조회를 붙들어 둔다. 그동안 화면에 그려진 글을 빠짐없이 기록한다.
      const pending: { release: () => void } = { release: () => {} };
      apiMocks.fetchStrategyRegistry.mockImplementation(
        () =>
          new Promise((resolve) => {
            pending.release = () => resolve(nextRegistry());
          }),
      );
      let claimedBeforeReload = false;
      let released = false;
      const observer = new MutationObserver(() => {
        if (
          !released &&
          document.body.textContent?.includes("그대로 두었습니다")
        ) {
          claimedBeforeReload = true;
        }
      });
      observer.observe(document.body, {
        subtree: true,
        childList: true,
        characterData: true,
      });
      try {
        await user.click(actionButton("분석하기"));
        const notice = await screen.findByText(
          /확인하신 사이에 등록표나 분석 규칙이 바뀌었습니다/,
        );
        await waitFor(() =>
          expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalledTimes(2),
        );
        expect(notice.parentElement?.textContent).toContain("불러오는 중");

        released = true;
        pending.release();
        await waitFor(() =>
          expect(notice.parentElement?.textContent).toContain(
            "바뀐 등록표에서도 쓸 수 있어 그대로 두었습니다",
          ),
        );
      } finally {
        observer.disconnect();
      }
      expect(claimedBeforeReload).toBe(false);
    });

    it("바뀐 등록표에서 쓸 수 없게 된 값은 고치지 않고 기본값 복원을 제안한다", async () => {
      const user = userEvent.setup();
      const calls: BacktestCreateRequest[] = [];
      const { support } = makeSupport({
        createBacktest: async (request) => {
          calls.push(request);
          if (calls.length === 1) throw registryVersionChangedError();
          return { backtestId: "BT-20260917-000003", status: "QUEUED" };
        },
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );

      // 새 등록표는 허용 이탈 폭의 하한을 올렸다. 지금 값(기본값 35)은 새 범위 밖이다.
      apiMocks.fetchStrategyRegistry.mockResolvedValue(
        nextRegistry((strategy) => ({
          ...strategy,
          axes: strategy.axes.map((axis) =>
            axis.name === "bandWidth"
              ? {
                  ...axis,
                  range: { min: 40, max: 50, step: 5 },
                  defaultValue: 40,
                }
              : axis,
          ),
        })),
      );
      await user.click(actionButton("분석하기"));

      const notice = await screen.findByText(
        /확인하신 사이에 등록표나 분석 규칙이 바뀌었습니다/,
      );
      await waitFor(() =>
        expect(notice.parentElement?.textContent).toContain(
          "쓸 수 없게 된 값이 있습니다",
        ),
      );
      const invalidList = screen.getByRole("status", {
        name: "다시 확인할 설정값",
      });
      expect(invalidList.textContent).toContain(
        "허용 범위(40~50퍼센트) 밖입니다",
      );
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );
      expect(calls).toHaveLength(1);

      await user.click(
        within(invalidList).getByRole("button", { name: "기본값 복원" }),
      );
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      // 복원을 눌러도 요청은 나가지 않는다. 보내는 것은 사용자가 다시 누를 때다.
      expect(calls).toHaveLength(1);

      await user.click(actionButton("분석하기"));
      await waitFor(() => expect(calls).toHaveLength(2));
      expect(calls[1].strategy.parameters.bandWidth).toBe(40);
      expect(calls[1].strategy.registryVersion).toBe("TEST-REGISTRY-003-2");
    });

    it("규칙을 판정하지 못하면 통과로 접지 않고 알리며 분석하기를 막는다", async () => {
      // T101. 등록표가 합성 값 축을 대소 비교하는 규칙을 실었다 — 판정할 수 없는 규칙이다.
      apiMocks.fetchStrategyRegistry.mockResolvedValue({
        ...registryFixture,
        strategies: registryFixture.strategies.map((strategy) =>
          strategy.strategyId === selectableStrategy.strategyId
            ? {
                ...strategy,
                validationRules: [
                  {
                    code: "MALFORMED_RULE",
                    detail: "이 문장은 보이면 안 된다.",
                    left: { axis: "resetTiming" },
                    operator: "GREATER_THAN" as const,
                    right: { value: 1 },
                  },
                ],
              }
            : strategy,
        ),
      });
      const user = userEvent.setup();
      const { support, requests } = makeSupport();
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));

      expect(
        await screen.findByText("설정 규칙을 판정하지 못했습니다."),
      ).toBeTruthy();
      expect(screen.queryByText("이 문장은 보이면 안 된다.")).toBeNull();
      await waitFor(() =>
        expect(reasonOf(actionButton("분석하기"))).toContain(
          "설정 규칙을 판정하지 못해",
        ),
      );
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );

      await user.click(actionButton("분석하기"));
      expect(requests).toHaveLength(0);
    });

    it("고른 카드의 워밍업을 계산할 수 없으면 기본 설정으로 판정하지 않고 판정하지 못했다고 보인다", async () => {
      // T100. 재검증이 워밍업 규칙의 축을 비워 복원을 기다리는 동안이다.
      const warmupAxis = selectableStrategy.axes.find(
        (axis) => axis.name === "driftWindow",
      )!;
      window.sessionStorage.setItem(
        STRATEGY_SETTINGS_STORAGE_KEY,
        JSON.stringify({
          version: 1,
          registryVersion: "OLD-REGISTRY",
          universeSize: 3,
          selectedStrategyId: selectableStrategy.strategyId,
          valuesByStrategy: {
            [selectableStrategy.strategyId]: { [warmupAxis.name]: 60 },
          },
          analysisCondition: { minEvaluationYears: 1, scenario: "FULL_PERIOD" },
          invalidValues: [],
        }),
      );
      const judged: number[] = [];
      const user = userEvent.setup();
      const { support } = makeSupport({
        judgeWarmup: (input) => {
          if (input.strategy.strategyId === selectableStrategy.strategyId) {
            judged.push(input.warmupTradingDays);
          }
          return judgeByWarmup(input);
        },
      });
      renderPage(support);

      let card = await findCardNamed(selectableStrategy.name);
      await waitFor(() =>
        expect(card.getAttribute("aria-checked")).toBe("true"),
      );
      expect(card.getAttribute("data-warmup")).toBe("unknown");
      expect(
        descriptionOf(card).match(/현재 설정으로 워밍업을 계산할 수 없습니다/),
      ).toBeTruthy();
      // 고를 수 없다는 뜻이 아니다. 기본 설정의 워밍업(45)으로 대신 판정했다면 'reduced'였다.
      // 호출 횟수로는 보지 않는다 — 재검증을 마치기 전 첫 렌더에서는 고른 전략이 없어 카드 목록이
      // 기본 설정으로 판정하는 것이 맞다.
      expect(card.getAttribute("aria-disabled")).toBe("false");
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );

      await user.click(screen.getByRole("button", { name: "기본값 복원" }));

      card = await findCardNamed(selectableStrategy.name);
      await waitFor(() =>
        expect(card.getAttribute("data-warmup")).toBe("reduced"),
      );
      expect(judged).toContain(warmupAxis.defaultValue);
    });
  });

  describe("분석 요청의 거절 코드마다 계약이 정한 처리를 한다(T099, 계약 §2)", () => {
    /** 서버 거절을 흉내 낸다. 화면은 오류 클래스가 아니라 `code`와 `details`만 읽는다. */
    function rejection(
      code: string,
      message: string,
      details?: Record<string, unknown>,
    ): Error {
      return Object.assign(new Error(message), { code, details });
    }

    /** 첫 요청만 `error`로 거절하고 그 뒤로는 접수한다. 보낸 요청은 모두 `calls`에 쌓는다. */
    function rejectFirst(error: Error) {
      const calls: BacktestCreateRequest[] = [];
      const { support } = makeSupport({
        createBacktest: async (request) => {
          calls.push(request);
          if (calls.length === 1) throw error;
          return { backtestId: "BT-20260917-000009", status: "QUEUED" };
        },
      });
      return { support, calls };
    }

    async function chooseAndAnalyze(user: ReturnType<typeof userEvent.setup>) {
      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
          "false",
        ),
      );
      await user.click(actionButton("분석하기"));
    }

    it("등록되지 않은 전략이면 전략 선택으로 되돌리고 등록표를 다시 받으며 다시 보내지 않는다", async () => {
      const user = userEvent.setup();
      const { support, calls } = rejectFirst(
        rejection("STRATEGY_NOT_REGISTERED", "등록되지 않은 전략입니다."),
      );
      renderPage(support);
      await chooseAndAnalyze(user);

      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain("전략을 다시 골라 주세요");
      await waitFor(() =>
        expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalledTimes(2),
      );
      const card = await findCardNamed(selectableStrategy.name);
      await waitFor(() =>
        expect(card.getAttribute("aria-checked")).toBe("false"),
      );
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "전략 카드를 먼저 골라 주세요",
      );
      expect(calls).toHaveLength(1);
    });

    it("요청을 보낸 뒤 다른 전략을 골랐으면 늦게 온 거절이 새로 고른 전략을 내려놓지 않는다", async () => {
      // 코드리뷰 지적. 보내는 동안에도 카드를 고를 수 있다.
      const user = userEvent.setup();
      const pending: { reject: (error: Error) => void } = { reject: () => {} };
      const calls: BacktestCreateRequest[] = [];
      const { support } = makeSupport({
        createBacktest: (request) => {
          calls.push(request);
          return new Promise((_, reject) => {
            pending.reject = reject;
          });
        },
      });
      renderPage(support);
      await chooseAndAnalyze(user);
      await waitFor(() => expect(calls).toHaveLength(1));

      await user.click(await findCardNamed(plainStrategy.name));
      // 단 제목은 '상세 설정'으로 고정이고, 고른 전략의 이름은 카드 첫 줄이 말한다.
      await waitFor(() =>
        expect(
          screen.getByText(plainStrategy.name, { selector: "strong" }),
        ).toBeTruthy(),
      );

      pending.reject(
        rejection("STRATEGY_NOT_REGISTERED", "등록되지 않은 전략입니다."),
      );

      // 등록표는 다시 받는다 — 보낸 전략이 목록에서 빠졌는지는 여전히 보여야 한다.
      await waitFor(() =>
        expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalledTimes(2),
      );
      const plainCard = await findCardNamed(plainStrategy.name);
      await waitFor(() =>
        expect(plainCard.getAttribute("aria-checked")).toBe("true"),
      );
      // 새로 고른 전략의 이야기가 아니므로 옛 전략의 거절 안내로 덮지 않는다.
      expect(screen.queryByText(/전략을 다시 골라 주세요/)).toBeNull();
      expect(calls).toHaveLength(1);
    });

    it("등록표를 읽지 못했으면 다시 시도를 제안하고, 누르기 전에는 다시 보내지 않는다", async () => {
      const user = userEvent.setup();
      const { support, calls } = rejectFirst(
        rejection(
          "REGISTRY_UNAVAILABLE",
          "전략 등록 정보를 읽을 수 없습니다. 잠시 후 다시 시도해 주세요.",
        ),
      );
      renderPage(support);
      await chooseAndAnalyze(user);

      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain("전략 등록 정보를 읽을 수 없습니다");
      const retry = within(alert).getByRole("button", { name: "다시 시도" });
      expect(calls).toHaveLength(1);

      await user.click(retry);
      await waitFor(() => expect(calls).toHaveLength(2));
      expect(await screen.findByText(/BT-20260917-000009/)).toBeTruthy();
    });

    it("허용 범위 밖이라 거절되면 개발용 문장 대신 등록표의 항목 이름으로 안내한다", async () => {
      const user = userEvent.setup();
      const bandWidth = selectableStrategy.axes.find(
        (axis) => axis.name === "bandWidth",
      )!;
      const { support, calls } = rejectFirst(
        rejection(
          "UNREGISTERED_PARAMETER_VALUE",
          `허용 범위를 벗어난 값입니다: strategyId=${selectableStrategy.strategyId}, 항목=bandWidth, 값=35, 허용 범위=AllowedRange[min=40]`,
          {
            strategyId: selectableStrategy.strategyId,
            axis: "bandWidth",
            value: 35,
            allowedRange: "AllowedRange[min=40]",
          },
        ),
      );
      renderPage(support);
      await chooseAndAnalyze(user);

      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain(bandWidth.label);
      expect(alert.textContent).not.toContain("strategyId=");
      expect(alert.textContent).not.toContain("AllowedRange");
      expect(calls).toHaveLength(1);
    });

    it("어느 항목인지 알 수 없는 범위 밖 거절은 일반 문장으로 안내한다", async () => {
      const user = userEvent.setup();
      const { support } = rejectFirst(
        rejection(
          "UNREGISTERED_PARAMETER_VALUE",
          "허용 범위를 벗어난 시작 금액입니다: 값=1, 허용 범위=x",
          {
            value: 1,
            allowedRange: "x",
          },
        ),
      );
      renderPage(support);
      await chooseAndAnalyze(user);

      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain(
        "허용 범위 밖이라 접수되지 않았습니다",
      );
      expect(alert.textContent).not.toContain("허용 범위=x");
    });
  });
});

describe("StrategySettingsPage 분석 조건(T065~T067, quickstart 7-2·8, SC-004)", () => {
  /** 기간 판정 흉내. 줄어든 기간과 그 원인을 001 응답 모양 그대로 넘긴다 */
  const reducedJudgement = (): WarmupJudgement => ({
    verdict: { state: "reduced", message: "통로가 지은 문장" },
    window: windowFixture,
    reduction: {
      startWithoutWarmup: "2019-09-02",
      bindingCandidate: "BETA.XKRX",
    },
  });

  function storeCondition(condition: Record<string, unknown>) {
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: registryFixture.registryVersion,
        universeSize: 3,
        selectedStrategyId: null,
        valuesByStrategy: {},
        analysisCondition: {
          minEvaluationYears: 1,
          scenario: "FULL_PERIOD",
          ...condition,
        },
        invalidValues: [],
      }),
    );
  }

  /** 분석 조건의 접힌 행을 모두 펼치고 영역을 돌려준다. 행마다 여닫으므로 폼을 보려면 열어야 한다. */
  async function openConditions(user: ReturnType<typeof userEvent.setup>) {
    const panel = screen.getByRole("region", { name: "분석 조건" });
    for (const toggle of Array.from(
      panel.querySelectorAll<HTMLButtonElement>(
        '[data-slot="setting-toggle"][aria-expanded="false"]',
      ),
    )) {
      await user.click(toggle);
    }
    return panel;
  }

  it("분석 조건에서 바꾼 기간과 금액이 그 행 값과 요청에 함께 실린다", async () => {
    const user = userEvent.setup();
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    fireEvent.change(within(panel).getByLabelText("시작일"), {
      target: { value: "2021-01-04" },
    });
    fireEvent.change(within(panel).getByLabelText("종료일"), {
      target: { value: "2023-12-28" },
    });
    const amount = registryFixture.analysisConditions.initialAmount;
    const chosenAmount = amount.default + amount.step;
    fireEvent.change(
      within(panel).getByRole("textbox", { name: /시작 금액/ }),
      {
        target: { value: String(chosenAmount) },
      },
    );

    const summary = screen.getByRole("region", { name: "분석 조건" });
    expect(summary.textContent).toContain("2021-01-04 ~ 2023-12-28");
    expect(summary.textContent).toContain(chosenAmount.toLocaleString("ko-KR"));

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.start).toBe("2021-01-04");
    expect(requests[0].conditions.end).toBe("2023-12-28");
    expect(requests[0].conditions.initialCash).toBe(chosenAmount.toFixed(2));
  });

  it("패널의 비용 가정은 통로가 준 수치·버전·임시 여부를 읽기 전용으로 보인다", async () => {
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);
    await waitFor(() =>
      expect(
        within(panel).getByRole("group", { name: "비용 가정" }).textContent,
      ).toContain(versionsFixture.costProfileVersion),
    );
    const group = within(panel).getByRole("group", { name: "비용 가정" });
    expect(group.textContent).toContain("수수료 1.5bp");
    expect(group.textContent).toContain("세금 20bp");
    expect(group.textContent).toContain("임시값");
  });

  it("비용 조건을 확인할 수 없으면 패널이 그 사실을 말하고 분석하기가 막힌다", async () => {
    const user = userEvent.setup();
    const { support, requests } = makeSupport({
      fetchAnalysisVersions: async () => null,
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    await waitFor(() =>
      expect(
        within(panel).getByText(/비용 조건을 확인할 수 없습니다/),
      ).toBeTruthy(),
    );
    expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe("true");
    expect(requests).toHaveLength(0);
  });

  it("평가할 수 있는 범위 옆 도움말이 그 범위인 까닭을 말한다", async () => {
    // SC-004. 범위는 분석 조건이 한 줄로 적고, 왜 그만큼인지는 그 옆에서 펼쳐 본다.
    const user = userEvent.setup();
    const { support } = makeSupport({ judgeWarmup: reducedJudgement });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    const range = `평가할 수 있는 범위는 ${windowFixture.start} ~ ${windowFixture.end}입니다.`;
    expect(within(panel).getByText(range, { exact: false })).toBeTruthy();
    // 고른 적 없는 범위를 두고 "줄었다"고 말하지 않는다.
    expect(panel.textContent).not.toContain("줄었");

    // 카드에도 적지 않는다 — 고를 수 있다는 뜻이라 선택을 가르지 않는다.
    const card = await findCardNamed(selectableStrategy.name);
    expect(within(card).queryByText(range)).toBeNull();

    await user.hover(
      within(panel).getByRole("button", { name: "평가할 수 있는 범위 도움말" }),
    );
    const cause = (await screen.findByText(/나섹터/)).textContent ?? "";
    expect(cause).toContain("표류 관측 창");
    expect(cause).toContain("2019-09-02");
  });

  it("워밍업이 앞을 깎지 않았으면 범위 옆에 까닭 도움말을 두지 않는다", async () => {
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    expect(
      within(panel).queryByRole("button", {
        name: "평가할 수 있는 범위 도움말",
      }),
    ).toBeNull();
  });

  it("최소 평가 기간을 바꾸면 그 값으로 카드 판정을 다시 한다", async () => {
    // quickstart 7-2. 같은 범위·워밍업인데 엄격(3년)이면 막히고 완화로 돌리면 풀린다.
    const user = userEvent.setup();
    const { support } = makeSupport({
      judgeWarmup: ({ minEvaluationYears }) =>
        minEvaluationYears >= 3
          ? {
              verdict: {
                state: "blocked",
                message: "최소 평가 기간(3년)에 못 미칩니다.",
              },
              window: null,
              reduction: null,
            }
          : {
              verdict: { state: "ok" },
              window: windowFixture,
              reduction: null,
            },
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    await user.click(within(panel).getByRole("radio", { name: "엄격(3년)" }));
    const card = await findCardNamed(selectableStrategy.name);
    await waitFor(() =>
      expect(card.getAttribute("data-warmup")).toBe("blocked"),
    );
    expect(reasonOf(actionButton("분석하기"))).toContain(
      "최소 평가 기간(3년)에 못 미칩니다",
    );

    await user.click(
      within(panel).getByRole("radio", { name: "완화(제한 없음)" }),
    );
    await waitFor(() => expect(card.getAttribute("data-warmup")).toBe("ok"));
  });

  it("고른 기간이 최소 평가 기간보다 짧으면 분석하기가 막히고 최소 평가 기간을 풀면 열린다", async () => {
    const user = userEvent.setup();
    storeCondition({ periodStart: "2022-01-03", periodEnd: "2022-06-30" });
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "최소 평가 기간(기본(1년))보다 짧습니다",
      ),
    );

    const panel = await openConditions(user);
    await user.click(
      within(panel).getByRole("radio", { name: "완화(제한 없음)" }),
    );
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.start).toBe("2022-01-03");
    expect(requests[0].conditions.end).toBe("2022-06-30");
  });

  it("보관된 기간이 지금 범위를 벗어나면 잘라 맞추지 않고 막으며, 되돌리면 범위 전체로 보낸다", async () => {
    // FR-017. 범위는 전략·워밍업을 따라 바뀌므로 고른 뒤에 벗어날 수 있다.
    const user = userEvent.setup();
    storeCondition({ periodStart: "2019-03-04", periodEnd: "2023-12-28" });
    const { support, requests } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const outOfRange = "범위(2020-01-02 ~ 2024-12-30) 밖입니다";
    await waitFor(() =>
      expect(reasonOf(actionButton("분석하기"))).toContain(outOfRange),
    );
    // 분석 기간 행은 사용자가 고른 기간을 그대로 보인다. 경계로 잘라 보이지 않는다.
    expect(screen.getByRole("region", { name: "분석 조건" }).textContent).toContain(
      "2019-03-04 ~ 2023-12-28",
    );

    const notice = screen
      .getAllByRole("status")
      .find((element) => element.textContent?.includes(outOfRange));
    expect(notice).toBeTruthy();
    await user.click(
      within(notice as HTMLElement).getByRole("button", {
        name: "범위 전체로 되돌리기",
      }),
    );

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].conditions.start).toBe(windowFixture.start);
    expect(requests[0].conditions.end).toBe(windowFixture.end);
  });

  it("원인을 받지 못하면 범위는 그대로 적고 까닭 도움말만 두지 않는다", async () => {
    // 통로가 `reduction`을 싣지 않으면 까닭을 지어내지 않는다. 범위 자체는 여전히 보인다.
    const user = userEvent.setup();
    const { support } = makeSupport({
      judgeWarmup: () => ({
        verdict: { state: "reduced", message: "평가 기간이 줄어듭니다." },
        window: windowFixture,
        reduction: null,
      }),
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);

    expect(
      within(panel).getByText(
        `평가할 수 있는 범위는 ${windowFixture.start} ~ ${windowFixture.end}입니다.`,
        { exact: false },
      ),
    ).toBeTruthy();
    expect(
      within(panel).queryByRole("button", {
        name: "평가할 수 있는 범위 도움말",
      }),
    ).toBeNull();
  });

  it("고르지 않은 기간(범위 전체)은 화면이 최소 평가 기간으로 다시 판정하지 않는다", async () => {
    // 범위 전체의 판정은 001 산식을 따르는 카드 판정이 맡는다. 달력 규약으로는 하루 모자라는 범위다.
    const user = userEvent.setup();
    const shortWindow = { start: "2023-06-01", end: "2024-05-31" };
    const { support } = makeSupport({
      judgeWarmup: () => ({
        verdict: { state: "ok" },
        window: shortWindow,
        reduction: null,
      }),
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));

    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    expect(reasonOf(actionButton("분석하기"))).not.toContain("최소 평가 기간");
  });

  it("고른 뒤 범위가 줄어 기간이 밖으로 나가면 그때 막는다", async () => {
    // 최소 평가 기간을 바꾸는 등으로 판정이 바뀌면 범위도 달라질 수 있다. 흉내는 엄격할수록 범위를 좁힌다.
    const user = userEvent.setup();
    const narrow = { start: "2022-01-03", end: "2024-12-30" };
    const { support, requests } = makeSupport({
      judgeWarmup: ({ minEvaluationYears }) => ({
        verdict: { state: "ok" },
        window: minEvaluationYears === 0 ? windowFixture : narrow,
        reduction: null,
      }),
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);
    await user.click(
      within(panel).getByRole("radio", { name: "완화(제한 없음)" }),
    );
    fireEvent.change(within(panel).getByLabelText("시작일"), {
      target: { value: "2021-01-04" },
    });
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );

    await user.click(within(panel).getByRole("radio", { name: "기본(1년)" }));

    await waitFor(() =>
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "범위(2022-01-03 ~ 2024-12-30) 밖입니다",
      ),
    );
    expect(screen.getByRole("region", { name: "분석 조건" }).textContent).toContain(
      "2021-01-04 ~ 2024-12-30",
    );
    expect(
      within(panel).getByLabelText("시작일").getAttribute("aria-invalid"),
    ).toBe("true");
    expect(requests).toHaveLength(0);
  });

  it("입력이 무효로 끝나 기본 금액으로 돌아가면 기본값을 사용자 값으로 담지 않는다", async () => {
    const user = userEvent.setup();
    const { support } = makeSupport();
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);
    const field = within(panel).getByRole("textbox", { name: /시작 금액/ });
    const amount = registryFixture.analysisConditions.initialAmount;

    fireEvent.focus(field);
    fireEvent.change(field, {
      target: { value: String(amount.default + amount.step) },
    });
    fireEvent.change(field, {
      target: { value: String(amount.max + amount.step) },
    });

    await waitFor(() => {
      const stored = JSON.parse(
        window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY) ?? "{}",
      );
      expect(stored.analysisCondition).toBeTruthy();
      expect(stored.analysisCondition.initialAmount).toBeUndefined();
    });
    expect(screen.getByRole("region", { name: "분석 조건" }).textContent).toContain(
      amount.default.toLocaleString("ko-KR"),
    );
  });

  it("전략을 고르지 않았어도 최소 평가 기간을 바꿔 막힌 카드를 풀 수 있다", async () => {
    // T102, FR-011a. 최소 평가 기간이 모든 카드를 막으면 카드를 고를 수 없으므로, 그 값을 되돌릴 입력이
    // 선택에 묶여 있으면 빠져나갈 길이 없다.
    const user = userEvent.setup();
    storeCondition({ minEvaluationYears: 3 });
    const { support } = makeSupport({
      judgeWarmup: ({ minEvaluationYears }) =>
        minEvaluationYears >= 3
          ? {
              verdict: {
                state: "blocked",
                message: "최소 평가 기간(3년)에 못 미칩니다.",
              },
              window: null,
              reduction: null,
            }
          : {
              verdict: { state: "ok" },
              window: windowFixture,
              reduction: null,
            },
    });
    const { container } = renderPage(support);

    const card = await findCardNamed(selectableStrategy.name);
    await waitFor(() =>
      expect(card.getAttribute("data-warmup")).toBe("blocked"),
    );
    await user.click(card);
    expect(card.getAttribute("aria-checked")).toBe("false");
    // 기본 화면에서 바로 쓰는 입력은 짧은 선택뿐이다(SC-005, 2026-09-30 개정). 날짜·금액 행은 접힌 채다.
    expect(
      container.querySelectorAll('input:not([type="radio"]), select, textarea'),
    ).toHaveLength(0);

    // 전략을 고르기 전에는 기간을 정할 범위가 없다. 날짜를 지어 보이지 않고, 펼칠 자리 없이 까닭을 늘 보인다(FR-003).
    const conditions = screen.getByRole("region", { name: "분석 조건" });
    expect(within(conditions).getByText(/전략을 고르면/)).toBeTruthy();
    expect(within(conditions).getByText("정해지지 않음")).toBeTruthy();
    const panel = await openConditions(user);
    expect(within(panel).queryByLabelText("시작일")).toBeNull();

    await user.click(
      within(panel).getByRole("radio", { name: "완화(제한 없음)" }),
    );
    await waitFor(() => expect(card.getAttribute("data-warmup")).toBe("ok"));
    await user.click(card);
    expect(card.getAttribute("aria-checked")).toBe("true");
  });

  it("바꾼 기간은 화면을 새로 그려도 남는다", async () => {
    // FR-017, quickstart 8.
    const user = userEvent.setup();
    const first = renderPage(makeSupport().support);

    await user.click(await findCardNamed(selectableStrategy.name));
    const panel = await openConditions(user);
    fireEvent.change(within(panel).getByLabelText("시작일"), {
      target: { value: "2021-01-04" },
    });
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "분석 조건" }).textContent).toContain(
        "2021-01-04 ~ 2024-12-30",
      ),
    );
    first.unmount();

    renderPage(makeSupport().support);
    await findCardNamed(selectableStrategy.name);
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "분석 조건" }).textContent).toContain(
        "2021-01-04 ~ 2024-12-30",
      ),
    );
  });
});

describe("StrategySettingsPage 공통 평가 기간 연결(T042, T098)", () => {
  /**
   * 합성 거래일 목록. 날마다 하루씩 이어진다 — 이 검사는 달력의 모양이 아니라 N번째 날을 쓴다는 것만 본다.
   * `commonEnd`는 워밍업 45일(기본 설정)이면 최소 평가 기간 1년을 채우고 90일이면 못 채우는 날로 두었다.
   */
  function syntheticWindow(): EvaluationWindow {
    const days = Array.from({ length: 91 }, (_, index) => {
      const date = new Date(Date.UTC(2024, 0, 1 + index));
      return date.toISOString().slice(0, 10);
    });
    return {
      commonStart: days[45],
      commonEnd: "2025-03-01",
      warmupTradingDays: 45,
      bindingCandidate: "BETA.XKRX",
      bindingSector: "BETA",
      excludedRanges: [],
      evaluable: true,
      datasetId: "TEST-DATASET-1",
      warmupBaseDate: days[0],
      tradingDaysFromBase: days,
      maxWarmupTradingDays: 90,
      excludedRangesFromBase: [],
    };
  }

  it("섹터 집합으로 한 번만 조회하고, 값을 바꾸는 동안에는 받아 둔 거래일로 다시 판정한다", async () => {
    const xhrSpy = vi.spyOn(XMLHttpRequest.prototype, "open");
    try {
      const user = userEvent.setup();
      const fetchEvaluationWindow = vi.fn<
        AnalysisSupport["fetchEvaluationWindow"]
      >(async () => syntheticWindow());
      const { support } = makeSupport({
        fetchEvaluationWindow,
        // 판정은 실제 통로의 연결을 쓴다. 조회만 흉내 낸다(T106).
        judgeWarmup: connectedAnalysisSupport.judgeWarmup,
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(async () =>
        expect(
          (await findCardNamed(selectableStrategy.name)).getAttribute(
            "data-warmup",
          ),
        ).toBe("reduced"),
      );
      expect(fetchEvaluationWindow).toHaveBeenCalledTimes(1);
      // 등록표에서 계산한 상한(표류 균형 맞추기의 90)과 섹터 코드를 싣는다. 전략은 싣지 않는다.
      expect(fetchEvaluationWindow.mock.calls[0][0]).toEqual({
        sectors: ["ALPHA", "BETA", "GAMMA"],
        maxWarmupTradingDays: 90,
      });

      await openSetting(user, "표류 관측 창");
      await user.click(screen.getByRole("radio", { name: "90거래일" }));

      const card = await findCardNamed(selectableStrategy.name);
      expect(card.getAttribute("data-warmup")).toBe("blocked");
      expect(
        descriptionOf(card).match(/최소 평가 기간\(1년\)에 못 미칩니다/),
      ).toBeTruthy();
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "true",
      );

      await user.click(screen.getByRole("radio", { name: "15거래일" }));
      await waitFor(async () =>
        expect(
          (await findCardNamed(selectableStrategy.name)).getAttribute(
            "data-warmup",
          ),
        ).toBe("reduced"),
      );

      expect(fetchEvaluationWindow).toHaveBeenCalledTimes(1);
      expect(xhrSpy).not.toHaveBeenCalled();
    } finally {
      xhrSpy.mockRestore();
    }
  });

  it("평가 기간을 받지 못하면 카드가 그 사실을 보이고 분석하기를 막는다", async () => {
    const user = userEvent.setup();
    const { support, requests } = makeSupport({
      judgeWarmup: connectedAnalysisSupport.judgeWarmup,
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(reasonOf(actionButton("분석하기"))).toContain(
        "공통 평가 기간을 확인할 수 없어",
      ),
    );
    const card = await findCardNamed(selectableStrategy.name);
    expect(card.getAttribute("data-warmup")).toBe("unknown");

    await user.click(actionButton("분석하기"));
    expect(requests).toHaveLength(0);
  });
});

describe("StrategySettingsPage 규칙 버전(T039, 004 GET /backtests/registry)", () => {
  function withRulesFor(
    status: string,
    extra: AnalysisVersions["strategyRules"] = [],
  ): AnalysisVersions {
    return {
      ...versionsFixture,
      strategyRules: [
        ...versionsFixture.strategyRules.filter(
          (row) => row.strategyId !== selectableStrategy.strategyId,
        ),
        {
          strategyId: selectableStrategy.strategyId,
          rulesVersion: "R-2",
          status,
          provisional: true,
        },
        ...extra,
      ],
    };
  }

  const withoutRow: AnalysisVersions = {
    ...versionsFixture,
    strategyRules: versionsFixture.strategyRules.filter(
      (row) => row.strategyId !== selectableStrategy.strategyId,
    ),
  };

  it.each([
    [
      "계산 규칙이 확정되지 않은 전략",
      withRulesFor("PENDING_DECISION"),
      "계산 규칙이 아직 확정되지 않아",
    ],
    ["등록표에 행이 없는 전략", withoutRow, "계산 규칙 버전을 확인할 수 없어"],
    [
      "등록된 행이 둘인 전략",
      withRulesFor("REGISTERED", [
        {
          strategyId: selectableStrategy.strategyId,
          rulesVersion: "R-3",
          status: "REGISTERED",
          provisional: true,
        },
      ]),
      "계산 규칙 버전을 확인할 수 없어",
    ],
  ])(
    "%s은 버전을 고르지 않고 분석하기를 막는다",
    async (_, versions, reason) => {
      const user = userEvent.setup();
      const { support, requests } = makeSupport({
        fetchAnalysisVersions: async () => versions,
      });
      renderPage(support);

      await user.click(await findCardNamed(selectableStrategy.name));
      await waitFor(() =>
        expect(reasonOf(actionButton("분석하기"))).toContain(reason),
      );

      await user.click(actionButton("분석하기"));
      expect(requests).toHaveLength(0);
    },
  );

  it("고른 전략의 등록된 버전을 싣는다", async () => {
    const user = userEvent.setup();
    const { support, requests } = makeSupport({
      fetchAnalysisVersions: async () => withRulesFor("REGISTERED"),
    });
    renderPage(support);

    await user.click(await findCardNamed(selectableStrategy.name));
    await waitFor(() =>
      expect(actionButton("분석하기").getAttribute("aria-disabled")).toBe(
        "false",
      ),
    );
    await user.click(actionButton("분석하기"));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].strategy.rulesVersion).toBe("R-2");
  });
});
