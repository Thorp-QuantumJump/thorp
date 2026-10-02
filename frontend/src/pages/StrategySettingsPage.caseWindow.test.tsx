import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SectorCatalogResult } from "@/api/sectors";
import { AuthContext, type AuthContextValue } from "@/auth/auth-context";
import type {
  AnalysisSupport,
  AnalysisVersions,
  WarmupJudgement,
} from "@/components/strategy/analysisSupport";
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
} from "@/diagnosis/selectionStorage";
import { authenticatedUser } from "@/test/authFixtures";
import {
  registryFixture,
  selectableStrategy,
} from "@/test/strategyRegistryFixtures";
import type { SectorSummary } from "@/types/sector";
import { StrategySettingsPage } from "./StrategySettingsPage";

/**
 * 011 사례 경로에서만 걸리는 003 분석 기간 제약(011 진입 계약 §3, T046·T047·T049).
 *
 * <p>주소에 `case`가 없으면 003은 지금과 같다. 그 사실도 여기서 함께 못 박는다 — 제약이 사례 없는
 * 진단까지 좁히면 003의 기존 흐름이 조용히 막힌다.
 */

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchStrategyRegistry: vi.fn(),
  fetchEvaluationWindow: vi.fn(),
  fetchCaseWindow: vi.fn(),
}));

vi.mock("@/api/sectors", async () => {
  const actual =
    await vi.importActual<typeof import("@/api/sectors")>("@/api/sectors");
  return { ...actual, fetchSectorCatalog: apiMocks.fetchSectorCatalog };
});

vi.mock("@/api/strategyRegistry", async () => {
  const actual = await vi.importActual<typeof import("@/api/strategyRegistry")>(
    "@/api/strategyRegistry",
  );
  return { ...actual, fetchStrategyRegistry: apiMocks.fetchStrategyRegistry };
});

vi.mock("@/api/regimeLink", () => ({
  fetchCaseWindow: apiMocks.fetchCaseWindow,
}));

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

const loadedCatalog: SectorCatalogResult = {
  status: "loaded",
  catalog: {
    mapVersion: "SEM-TEST-1",
    isSample: false,
    sectors: [
      sector("ALPHA", "가섹터"),
      sector("BETA", "나섹터"),
      sector("GAMMA", "다섹터"),
    ],
  },
};

function storeSelection() {
  window.sessionStorage.setItem(
    SECTOR_SELECTION_STORAGE_KEY,
    JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: "SEM-TEST-1",
      selected: [
        { code: "ALPHA", weightTenths: 334 },
        { code: "BETA", weightTenths: 333 },
        { code: "GAMMA", weightTenths: 333 },
      ],
      weightsTouched: true,
    }),
  );
}

const RULES_VERSION = "RULES-TEST-1";
const versionsFixture: AnalysisVersions = {
  strategyRules: registryFixture.strategies.map((strategy) => ({
    strategyId: strategy.strategyId,
    rulesVersion: RULES_VERSION,
    status: "REGISTERED",
    provisional: true,
  })),
  costProfileVersion: "PROVISIONAL-DEMO-1",
  costProfileProvisional: true,
  costRates: { commissionBps: 1.5, spreadBps: 5, slippageBps: 2, taxBps: 20 },
};

/** 평가 가능 범위와 거래일 달력. 사례의 `d0`는 이 안에 있다. */
const RANGE = { start: "2011-01-03", end: "2012-12-28" };

/**
 * 거래일 달력. 2011~2012년의 평일을 담아 워밍업 45거래일을 셀 수 있게 한다.
 *
 * 휴장일을 반영하지 않은 대역이다 — 이 검사가 보는 것은 **화면이 달력 목록을 세는가**이지 어떤 날이
 * 휴장인가가 아니다. 휴장 판정은 001이 하고 목록으로 내려온다.
 */
const TRADING_DAYS = ((): string[] => {
  const days: string[] = [];
  for (
    let time = Date.UTC(2011, 0, 3);
    time <= Date.UTC(2012, 11, 28);
    time += 86_400_000
  ) {
    const day = new Date(time);
    const weekday = day.getUTCDay();
    if (weekday === 0 || weekday === 6) continue;
    days.push(day.toISOString().slice(0, 10));
  }
  return days;
})();

/** 사례의 `d0`. 달력 안에 있어야 워밍업을 셀 수 있다. */
const CASE_D0 = "2012-04-02";
const WARMUP = selectableStrategy.warmupTradingDays;
/** 화면이 내야 할 시작일 상한: `d0`에서 워밍업만큼 앞선 거래일. */
const EXPECTED_START_MAX = TRADING_DAYS[TRADING_DAYS.indexOf(CASE_D0) - WARMUP];

const CASE_ID = "FIELD:LABOR:2026-07-01:MACRO-1:REG-3:REGIME-V1~2012-03";

const caseWindowResponse = {
  basis: { kind: "HISTORICAL_REVISION" as const },
  caseId: CASE_ID,
  endMonth: "2012-03",
  postWindow: {
    d0: CASE_D0,
    d21: "2012-05-02",
    d63: "2012-07-03",
    lastAvailable: "2012-07-03",
    completedTradingDays: 63,
    complete21: true,
    complete63: true,
  },
};

function makeSupport(overrides: Partial<AnalysisSupport> = {}) {
  const support: AnalysisSupport = {
    fetchAnalysisVersions: async () => versionsFixture,
    fetchEvaluationWindow: async () => ({
      commonStart: RANGE.start,
      commonEnd: RANGE.end,
      warmupTradingDays: WARMUP,
      bindingCandidate: "ALPHA.XKRX",
      bindingSector: "ALPHA",
      excludedRanges: [],
      evaluable: true,
      datasetId: "KRX-TEST",
      warmupBaseDate: TRADING_DAYS[0],
      tradingDaysFromBase: TRADING_DAYS,
      maxWarmupTradingDays: TRADING_DAYS.length - 1,
      excludedRangesFromBase: [],
    }),
    judgeWarmup: (): WarmupJudgement => ({
      verdict: { state: "ok" },
      window: RANGE,
      reduction: null,
    }),
    createBacktest: async () => ({
      backtestId: "BT-20260916-000001",
      publicToken: "9f2cabcdef0123456789abcdef012345",
      status: "QUEUED" as const,
    }),
    ...overrides,
  };
  return support;
}

/** 어디로 이동했는지만 읽는다. 011 화면 자체는 이 검사의 관심 밖이다. */
function Landed() {
  const location = useLocation();
  return <p data-testid="landed">{location.pathname + location.search}</p>;
}

function renderPage(entry: string, support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: "authenticated",
    user: authenticatedUser,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
    updateNickname: vi.fn(),
    completeSignIn: vi.fn(),
  };

  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route
            path="/strategy"
            element={<StrategySettingsPage support={support} />}
          />
          <Route path="/regime-link" element={<Landed />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** 전략 카드를 고르고 '분석 기간' 행을 펼친다. 행마다 여닫으므로 폼을 보려면 그 행을 열어야 한다. */
async function openConditions(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    await screen.findByRole("radio", {
      name: new RegExp(selectableStrategy.name),
    }),
  );
  await user.click(
    screen.getByRole("button", {
      name: (name, element) =>
        element.getAttribute("data-slot") === "setting-toggle" && name.startsWith("분석 기간"),
    }),
  );
}

beforeEach(() => {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loadedCatalog);
  apiMocks.fetchStrategyRegistry.mockResolvedValue(registryFixture);
  apiMocks.fetchCaseWindow.mockResolvedValue(caseWindowResponse);
  storeSelection();
});

afterEach(() => {
  vi.clearAllMocks();
  window.sessionStorage.clear();
});

describe("003 분석 기간의 사례 제약 (011 진입 계약 §3)", () => {
  it("사례가 있으면 시작일 상한과 종료일 하한을 달력에서 세어 걸고 고정 문구를 보인다", async () => {
    const user = userEvent.setup();
    renderPage(`/strategy?case=${encodeURIComponent(CASE_ID)}`, makeSupport());
    await openConditions(user);

    await waitFor(() =>
      expect(apiMocks.fetchCaseWindow).toHaveBeenCalledWith(
        CASE_ID,
        expect.any(AbortSignal),
      ),
    );

    const start = await screen.findByLabelText("시작일");
    const end = screen.getByLabelText("종료일");
    // 화면이 날짜를 만들지 않는다. 받은 달력에서 워밍업만큼 거슬러 센 날이어야 한다.
    await waitFor(() =>
      expect(start.getAttribute("max")).toBe(EXPECTED_START_MAX),
    );
    expect(EXPECTED_START_MAX < CASE_D0).toBe(true);
    expect(end.getAttribute("min")).toBe("2012-07-03");
    expect(
      screen.getByText(/이 사례\(2012년 3월\) 직후 구간을 포함해야 하므로/),
    ).toBeTruthy();
  });

  it("사례에서 들어오면 분석 기간을 사례 직후 구간까지 미리 맞추고 그렇다고 말한다 (S15P21E205-450)", async () => {
    const user = userEvent.setup();
    renderPage(`/strategy?case=${encodeURIComponent(CASE_ID)}`, makeSupport());
    await openConditions(user);

    const end = (await screen.findByLabelText("종료일")) as HTMLInputElement;
    await waitFor(() => expect(end.value).toBe("2012-07-03"));
    const start = screen.getByLabelText("시작일") as HTMLInputElement;
    expect(start.value <= EXPECTED_START_MAX).toBe(true);
    expect(start.value >= RANGE.start).toBe(true);
    expect(screen.getByText(/분석 기간을 미리 맞춰 두었습니다/)).toBeTruthy();
  });

  it("하한 밖 기간을 고르면 저장하지 않고 이유를 말한다", async () => {
    const user = userEvent.setup();
    renderPage(`/strategy?case=${encodeURIComponent(CASE_ID)}`, makeSupport());
    await openConditions(user);
    const end = await screen.findByLabelText("종료일");
    await waitFor(() => expect(end.getAttribute("min")).toBe("2012-07-03"));

    // 사후 구간이 끝나기 전에 분석을 끝내는 기간이다. 계약이 막으라고 정한 자리다.
    await user.clear(end);
    await user.type(end, "2012-05-04");

    // 칸의 날짜를 반영하지 않았으니 '분석하기'가 그 사실을 말하며 막힌다(2026-10-01).
    await waitFor(() =>
      expect(document.body.textContent).toContain(
        "분석 기간의 날짜를 다시 확인해 주세요.",
      ),
    );
  });

  it("접수되면 곧바로 011 화면으로 base와 case를 들고 간다", async () => {
    const user = userEvent.setup();
    renderPage(`/strategy?case=${encodeURIComponent(CASE_ID)}`, makeSupport());
    await user.click(
      await screen.findByRole("radio", {
        name: new RegExp(selectableStrategy.name),
      }),
    );

    const analyze = await screen.findByRole("button", { name: "분석하기" });
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("false"),
    );
    await user.click(analyze);

    const landed = await screen.findByTestId("landed");
    const url = new URL(landed.textContent ?? "", "http://localhost");
    expect(url.pathname).toBe("/regime-link");
    expect(url.searchParams.get("base")).toBe(
      "9f2cabcdef0123456789abcdef012345",
    );
    expect(url.searchParams.get("case")).toBe(CASE_ID);
  });

  it("앞 단계로 돌아가는 길이 사례를 그대로 들고 간다", async () => {
    // 사례를 떨구고 돌아가면 기간 제약이 말없이 풀린 채 다른 흐름으로 빠진다(진입 계약 §2).
    renderPage(`/strategy?case=${encodeURIComponent(CASE_ID)}`, makeSupport());

    const back = await screen.findByRole("link", { name: /투자 대상 고르기/ });
    const href = back.getAttribute("href") ?? "";
    expect(href.startsWith("/diagnosis/sectors?")).toBe(true);
    // 글자가 아니라 값으로 본다. 인코딩 규약이 달라도 들고 가는 사례는 같아야 한다.
    expect(new URLSearchParams(href.split("?")[1]).get("case")).toBe(CASE_ID);
  });

  it("사례가 없으면 구간을 묻지도 않고 기간 제약도 걸지 않는다", async () => {
    const user = userEvent.setup();
    renderPage("/strategy", makeSupport());
    await openConditions(user);

    const start = await screen.findByLabelText("시작일");
    expect(start.getAttribute("max")).toBe(RANGE.end);
    expect(apiMocks.fetchCaseWindow).not.toHaveBeenCalled();
    expect(screen.queryByText(/직후 구간을 포함해야 하므로/)).toBeNull();
  });

  it("공통 평가 가능 범위가 사후 구간을 덮지 못하면 분석을 막고 이유를 보인다", async () => {
    const user = userEvent.setup();
    const narrow = { start: "2012-01-02", end: "2012-05-02" };
    renderPage(
      `/strategy?case=${encodeURIComponent(CASE_ID)}`,
      makeSupport({
        judgeWarmup: (): WarmupJudgement => ({
          verdict: { state: "ok" },
          window: narrow,
          reduction: null,
        }),
      }),
    );
    await user.click(
      await screen.findByRole("radio", {
        name: new RegExp(selectableStrategy.name),
      }),
    );

    const analyze = await screen.findByRole("button", { name: "분석하기" });
    await waitFor(() =>
      expect(analyze.getAttribute("aria-disabled")).toBe("true"),
    );
    expect(document.body.textContent).toContain(
      "고른 사례의 직후 구간을 덮을 수 없습니다",
    );
  });
});
