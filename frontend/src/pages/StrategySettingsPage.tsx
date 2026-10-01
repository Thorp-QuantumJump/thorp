import { ArrowLeft } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { fetchCaseWindow } from "@/api/regimeLink";
import { fetchSectorCatalog, type SectorCatalogResult } from "@/api/sectors";
import {
  fetchStrategyRegistry,
  normalizeStrategyRegistryError,
} from "@/api/strategyRegistry";
import { NotReadyNotice } from "@/components/common/NotReadyNotice";
import {
  StepOrbButton,
  type StepOrbStage,
} from "@/components/common/StepOrbButton";
import { InlineHelp } from "@/components/common/InlineHelp";
import { SECTOR_SELECTION_PATH } from "@/components/result/prefillStrategySettings";
import { buildSectorSelectionPath } from "@/marketflow/handoff";
import { AppHeader } from "@/components/navigation/AppHeader";
import { ProvisionalBadge } from "@/components/paramsearch/ProvisionalBadge";
import { ActionButtons } from "@/components/strategy/ActionButtons";
import {
  AnalysisConditionsPanel,
  type PeriodMode,
} from "@/components/strategy/AnalysisConditionsPanel";
import {
  ANALYSIS_VERSIONS_UNAVAILABLE,
  EVALUATION_WINDOW_UNAVAILABLE,
  BacktestRejectionCode,
  RULES_PENDING_DECISION,
  RULES_VERSION_UNAVAILABLE,
  readBacktestRejection,
  resolveRulesVersion,
  type AnalysisSupport,
  type AnalysisVersions,
  type BacktestRejection,
  type WarmupJudgement,
} from "@/components/strategy/analysisSupport";
import {
  buildCasePeriodConstraint,
  CASE_PERIOD_PREFILLED_NOTE,
  defaultCasePeriod,
  rangeCoversCase,
  type CasePeriodConstraint,
} from "@/components/strategy/casePeriodConstraint";
import { defaultAnalysisSupport } from "@/components/strategy/connectedAnalysisSupport";
import { DetailSettingsPanel } from "@/components/strategy/DetailSettingsPanel";
import {
  describeReducedCause,
  type ReducedPeriodInput,
  type WarmupCause,
} from "@/components/strategy/reducedPeriod";
import type { RegimeLinkCaseWindow } from "@/types/regimeLink";
import type { TargetWeightRow } from "@/components/strategy/TargetWeightEditor";
import {
  RegistryChangedNotice,
  type RegistryReloadPhase,
} from "@/components/strategy/RegistryChangedNotice";
import {
  collectSharedNotices,
  type WarmupVerdict,
} from "@/components/strategy/cardNotices";
import {
  StrategyCardList,
  type RegistryViewState,
} from "@/components/strategy/StrategyCardList";
import { StrategyNoticeList } from "@/components/strategy/StrategyNoticeList";
import { writeStoredSelection } from "@/diagnosis/selectionStorage";
import {
  distributeEven,
  formatWeightTenths as formatDisplayWeight,
  summarizeWeights,
} from "@/diagnosis/weights";
import { useEvaluationWindow } from "@/hooks/useEvaluationWindow";
import { useSectorSelection } from "@/hooks/useSectorSelection";
import {
  useStrategySettings,
  type InvalidStoredValue,
} from "@/hooks/useStrategySettings";
import type {
  BacktestAccepted,
  BacktestRequestStatus,
} from "@/types/backtestRequest";
import type { RegistryResponse, StrategyView } from "@/types/strategyRegistry";
import {
  isWithin,
  meetsMinimumYears,
  type AnalysisPeriod,
} from "@/utils/analysisPeriod";
import { buildBacktestRequest } from "@/utils/analysisRequest";
import { evaluationWindowQuery } from "@/utils/evaluationWindow";
import { checkRules } from "@/utils/validationRules";
import heading from '@/components/common/PageHeading.module.css'
import styles from "./StrategySettingsPage.module.css";

interface StrategySettingsPageProps {
  /**
   * 분석을 시작하기 위해 바깥에서 받아야 하는 세 통로(규칙·비용 버전, 워밍업 판정, 요청 전송).
   *
   * 기본값은 `defaultAnalysisSupport`로, 001 평가 기간 조회와 004 규칙·비용 버전 조회, 요청 전송이 모두 실제
   * 모듈이다(T107·T110). 받지 못한 통로가 있으면 '분석하기'가 그 이유로 막힌다. 검사는 흉내를 넘겨 요청
   * 조립을 고정한다.
   */
  support?: AnalysisSupport;
}

/** 접수 결과의 상태 이름. 그대로 보이면 사용자가 읽을 수 없다. */
const statusText: Record<BacktestRequestStatus, string> = {
  QUEUED: "대기 중",
  RUNNING: "실행 중",
  COMPLETED: "완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
};

const REGISTRY_NOT_LOADED =
  "전략 설정을 불러오기 전에는 분석을 시작할 수 없습니다.";
const SELECT_STRATEGY_FIRST = "전략 카드를 먼저 골라 주세요.";
/** 분석 기간을 아직 정하지 않았다(2026-10-01). 범위 전체로 대신 채우지 않는다 */
const PERIOD_NOT_CHOSEN =
  "분석 기간을 정해 주세요. 날짜를 고르거나 '최대 범위 선택'을 누르면 됩니다.";
/** 날짜 칸에 반영하지 않은 무효 날짜가 남았다. 칸에 보이는 날짜와 보낼 기간이 갈리므로 막는다(2026-10-01) */
const PERIOD_DRAFT_INVALID = "분석 기간의 날짜를 다시 확인해 주세요.";
const REASON_MISSING = "이 전략을 고를 수 없는 이유를 확인하지 못했습니다.";
const INVALID_STORED_VALUES =
  "이전에 저장한 설정값 중 현재 등록표에서 쓸 수 없는 값이 있습니다. 기본값을 복원해 주세요.";
const WARMUP_UNCOMPUTABLE = "현재 설정으로 워밍업을 계산할 수 없습니다.";
const RULES_VIOLATED =
  "설정 규칙을 어기는 값이 있습니다. 전략 설정 아래의 안내를 확인해 주세요.";
const RULES_UNJUDGED = "설정 규칙을 판정하지 못해 분석을 시작할 수 없습니다.";
const VERSIONS_LOADING = "분석 조건 버전을 확인하는 중입니다.";
const SUBMIT_FAILED = "분석 요청을 보내지 못했습니다.";
const STRATEGY_NOT_REGISTERED_MESSAGE =
  "고른 전략을 지금은 분석할 수 없어 요청이 접수되지 않았습니다. 전략 설정을 다시 불러왔으니 전략을 다시 골라 주세요.";
const PARAMETER_REJECTED_GENERAL =
  "설정값이 허용 범위 밖이라 접수되지 않았습니다. 전략 설정과 분석 조건을 확인해 주세요.";
/** 고른 기간이 지금 평가할 수 있는 범위 밖이다. 범위는 전략·워밍업을 따라 바뀌므로 고른 뒤에 벗어날 수 있다 */
function periodOutOfRangeMessage(range: AnalysisPeriod): string {
  return `고른 분석 기간이 지금 평가할 수 있는 범위(${range.start} ~ ${range.end}) 밖입니다. 기간을 다시 고르거나 '최대 범위 선택'을 눌러 주세요.`;
}

/** 고른 기간이 최소 평가 기간보다 짧다(FR-011a). 후보의 라벨은 등록표에서 그대로 온다 */
function periodTooShortMessage(label: string): string {
  return `고른 분석 기간이 최소 평가 기간(${label})보다 짧습니다. 기간을 늘리거나 최소 평가 기간을 바꿔 주세요.`;
}

/**
 * '분석하기'가 막힌 이유 문장의 id. 문장은 원 바로 옆에 놓이고, 원은 `aria-describedby`로 이 문장을 가리킨다.
 */
const ANALYZE_REASON_ID = "action-analyze-reason";

const RANGE_NEEDS_STRATEGY =
  "전략을 고르면 평가할 수 있는 범위가 정해지고 그 안에서 기간을 바꿀 수 있습니다.";

/**
 * 011 사례 경로에서만 쓰는 문장들(011 진입 계약 §3).
 *
 * 공통 평가 가능 범위가 사후 구간을 덮지 못하면 그 전략으로는 이 사례를 볼 수 없다. 사례를 바꾸거나
 * 빼지 않고(FR-017) 왜 고를 수 없는지만 말한다.
 */
const CASE_WINDOW_LOADING = "고른 사례의 직후 구간을 확인하는 중입니다.";
const CASE_WINDOW_UNAVAILABLE =
  "고른 사례의 직후 구간을 확인하지 못해 분석을 시작할 수 없습니다. 잠시 후 다시 시도해 주세요.";
const CASE_RANGE_UNCOVERABLE =
  "이 전략의 자료로는 고른 사례의 직후 구간을 덮을 수 없습니다. 다른 전략을 고르거나 투자 대상 고르기에서 과거 사례를 다시 골라 주세요.";

const REQUEST_BUILD_FAILED =
  "분석 요청을 만들지 못했습니다. 화면을 새로 고친 뒤 조건을 다시 확인해 주세요.";

/**
 * 비중 합계가 어긋났다는 한 문장. 지금 합계를 함께 적어 얼마나 어긋났는지 보이게 한다.
 * 표시 형식은 002 화면과 같은 `formatWeightTenths`를 쓴다 — 같은 선택이 두 화면에서 다른 숫자로
 * 보이지 않게 한다.
 */
function weightsUnbalancedMessage(totalTenths: number): string {
  return `목표 비중의 합계가 100%가 아닙니다(현재 ${formatDisplayWeight(totalTenths)}%). 전략 설정의 목표 비중에서 맞춰 주세요.`;
}

/**
 * 허용 범위 밖이라 거절되었다는 한 문장(400 `UNREGISTERED_PARAMETER_VALUE`).
 *
 * <p><b>서버 메시지를 그대로 보이지 않는다.</b> 그 문장은 `strategyId=…, 항목=…`처럼 로그용으로 짜여
 * 사용자가 읽을 수 없다. 응답 `details.axis`로 고른 전략에서 항목을 찾아 등록표의 라벨을 쓰고, 찾지
 * 못하면(시작 금액이거나 `details`가 없으면) 어느 항목인지 지어내지 않고 일반 문장을 쓴다.
 */
function parameterRejectedMessage(
  rejection: BacktestRejection,
  strategy: StrategyView,
): string {
  const axisName = rejection.details?.axis;
  const axis =
    typeof axisName === "string"
      ? strategy.axes.find((candidate) => candidate.name === axisName)
      : undefined;
  if (!axis) return PARAMETER_REJECTED_GENERAL;
  return `'${axis.label}' 값이 허용 범위 밖이라 접수되지 않았습니다. 전략 설정에서 값을 확인해 주세요.`;
}

type Submission =
  | { status: "idle" }
  | { status: "sending" }
  | { status: "accepted"; accepted: BacktestAccepted }
  /** `retryable`이면 같은 설정으로 다시 보낼 수 있다고 제안한다. 누르기 전에는 보내지 않는다 */
  | { status: "failed"; message: string; retryable?: boolean };

interface InvalidValueListProps {
  /** 이 목록이 무엇에 대한 것인가. 보조기술이 두 목록을 구분할 이름이다 */
  label: string;
  entries: InvalidStoredValue[];
  /** 복원할 수 있는 항목 하나. 전략 축인지 분석 조건인지는 받는 쪽이 가른다 */
  onRestore: (entry: InvalidStoredValue) => void;
}

/**
 * 현재 등록표에서 쓸 수 없게 된 보관값을 알린다(FR-017).
 *
 * <p><b>이유를 말하고 복원 선택을 준다. 값을 대신 고치지 않는다.</b> 복원할 수 있는 항목에만
 * 버튼을 두는데, 등록표에서 전략이나 항목 자체가 사라진 경우는 되돌릴 자리가 없어 알리기만
 * 한다.
 */
function InvalidValueList({
  label,
  entries,
  onRestore,
}: InvalidValueListProps) {
  return (
    <ul className={styles.invalidList} role="status" aria-label={label}>
      {entries.map((entry) => (
        <li key={`${entry.strategyId ?? "-"}:${entry.axisName ?? "-"}`}>
          <span>{entry.message}</span>
          {entry.restorable && entry.axisName !== null ? (
            <button
              type="button"
              className={styles.restoreButton}
              onClick={() => onRestore(entry)}
            >
              기본값 복원
            </button>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

/**
 * 전략 선택 화면(US1, FR-001·FR-002·FR-018). 흐름은 002 섹터·비중 → <b>003 전략 선택</b> →
 * 004 분석 / 007 자동 비교다.
 *
 * <p><b>위에서 아래로 흐른다(2026-09-30).</b> 전략 카드를 가로 한 줄에서 고르고, 그 아래 '상세 설정' 카드에서
 * 분석 조건과 전략 설정을 두 열로 확인·조정한 뒤, 두 카드 사이 틈에 걸친 원으로 넘어간다. 투자 대상 고르기
 * (STEP 01)와 같은 짜임이다. 설정은 항목마다 한 행이 지금 값을 늘 보이고, 후보가 몇 개뿐인 선택만 행 오른쪽에서
 * 바로 고르며, 날짜·금액·숫자는 값을 눌러 편다(SC-005, 2026-09-30 개정). 행동은 '분석하기'와 '설정 조합 자동
 * 비교' 둘이다.
 *
 * <p><b>바꾼 값은 그 자리에서 판정한다(US2, FR-008·FR-011).</b> 전략 설정에서 값을 바꾸면 규칙 위반,
 * 워밍업, 고른 카드의 판정, 기본 설정 표시, '분석하기'의 막힘이 한 렌더 안에서 다시 계산된다. 값을
 * 만지는 동안 서버로 요청이 나가지 않는다. 최종 판정은 접수 시점에 서버가 다시 하고, 그 사이 등록표가
 * 바뀌어 409로 거절되면 다시 받아 알리되 자동으로 다시 보내지 않는다(research R9).
 *
 * <p><b>등록표를 받기 전에는 아무것도 시작하지 않는다.</b> 조회 중·실패에는 카드를 고를 수 없고
 * 두 버튼이 막히며, 임시 값이나 예비 등록표로 대신 동작하는 경로가 없다(FR-018, SC-008).
 *
 * <p><b>앞 단계를 마치지 않았으면 기본값을 만들지 않는다.</b> 002가 확정한 섹터·비중은
 * `useSectorSelection` 한 통로로만 읽고, 없거나 손상되었거나 두 개 미만이면 앞 단계로 되돌린다
 * (spec Edge Cases).
 *
 * <p><b>아직 알 수 없는 값은 알 수 없다고 보인다.</b> 규칙·비용 버전(②)과 공통 평가 기간(①)은
 * `support`로 받는다. 받지 못한 통로가 있으면 '분석하기'는 이유와 함께 막히고 카드는 판정하지 못했다는
 * 사실을 보인다 — 값을 지어내 채우지 않는다(원칙 IV·VI).
 */
export function StrategySettingsPage({
  support = defaultAnalysisSupport,
}: StrategySettingsPageProps) {
  const navigate = useNavigate();

  // --- 011 사례 경로(진입 계약 §2·§3) ------------------------------------------------------
  //
  // 주소에 `case`가 없으면 이 절은 통째로 잠들어 있고 003의 동작은 지금과 같다. 있으면 사례의 사후
  // 구간을 한 번 읽어 분석 기간의 상한·하한을 더하고, 접수되면 011 화면으로 이어 간다.
  const [params] = useSearchParams();
  const caseId = params.get("case");
  const [caseWindow, setCaseWindow] = useState<
    | { status: "loading" }
    | {
        status: "loaded";
        postWindow: RegimeLinkCaseWindow["postWindow"];
        endMonth: string;
      }
    | { status: "unavailable" }
  >({ status: "loading" });

  useEffect(() => {
    if (caseId === null || caseId === "") return;
    const controller = new AbortController();
    setCaseWindow({ status: "loading" });
    fetchCaseWindow(caseId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return;
        setCaseWindow({
          status: "loaded",
          postWindow: next.postWindow,
          endMonth: next.endMonth,
        });
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setCaseWindow({ status: "unavailable" });
      });
    return () => controller.abort();
  }, [caseId]);

  // --- 002 경계: 섹터 목록을 받아 앞 단계의 선택을 확인한다 ---------------------------------
  const [catalog, setCatalog] = useState<SectorCatalogResult | null>(null);
  const [catalogReload, setCatalogReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setCatalog(null);
    void fetchSectorCatalog(controller.signal).then((next) => {
      if (active) setCatalog(next);
    });
    return () => {
      active = false;
      controller.abort();
    };
  }, [catalogReload]);

  const reloadCatalog = useCallback(
    () => setCatalogReload((count) => count + 1),
    [],
  );

  // 목록과 그 대응표 버전을 한 덩이로 넘긴다. 따로 넘기면 서로 다른 조회의 결과가 짝지어질 수 있다.
  const selection = useSectorSelection(
    catalog?.status === "loaded" ? catalog.catalog : null,
  );
  const universeSize =
    selection.status === "ready" ? selection.selection.items.length : null;

  /**
   * 이 화면에서 고친 목표 비중. 키는 섹터 코드이고 값은 0.1%p 단위 정수다.
   *
   * <p>`null`은 아직 아무것도 고치지 않았다는 뜻이며, 그때는 앞 단계가 넘긴 값을 그대로 쓴다.
   * 고친 값은 <b>앞 단계와 같은 보관 항목</b>에 다시 적는다 — 새로고침하거나 앞 단계를 다녀와도
   * 방금 정한 비중이 남아 있어야 하고, 그 보관 항목을 읽는 다른 화면이 서로 다른 값을 보면 안 된다.
   */
  const [weightOverrides, setWeightOverrides] = useState<Record<
    string,
    number
  > | null>(null);

  /**
   * 요청과 화면이 함께 읽는 선택 한 벌. <b>고친 비중이 반영된 값이다.</b>
   *
   * <p>고친 값을 화면에만 반영하고 요청은 앞 단계의 값으로 보내면, 사용자가 보고 있는 비중과
   * 실제로 계산되는 비중이 갈라진다.
   */
  const effectiveSelection = useMemo(() => {
    if (selection.status !== "ready") return null;
    if (weightOverrides === null) return selection.selection;
    return {
      mapVersion: selection.selection.mapVersion,
      items: selection.selection.items.map((item) => ({
        ...item,
        weightTenths: weightOverrides[item.sectorId] ?? item.weightTenths,
      })),
    };
  }, [selection, weightOverrides]);

  /** 지금 화면에 보이는 비중의 합계다. 100% 판정도 이 값으로 한다. */
  const effectiveWeights = useMemo(() => {
    const items = effectiveSelection?.items ?? [];
    return summarizeWeights(
      items.map((item) => item.sectorId),
      Object.fromEntries(
        items.map((item) => [item.sectorId, item.weightTenths]),
      ),
    );
  }, [effectiveSelection]);

  /**
   * 고친 비중을 상태와 보관 항목에 함께 남긴다.
   *
   * <p>보관이 막힌 환경에서도 화면 안의 값은 그대로 둔다. 저장하지 못했다고 방금 친 값을
   * 되돌리면 사용자는 자기가 무엇을 잘못했는지 알 수 없다.
   */
  const commitWeights = useCallback(
    (next: Record<string, number>) => {
      if (effectiveSelection === null) return;
      setWeightOverrides(next);
      writeStoredSelection({
        mapVersion: effectiveSelection.mapVersion,
        selectedCodes: effectiveSelection.items.map((item) => item.sectorId),
        weightTenths: next,
        // 사용자가 직접 정한 값이라, 앞 단계에서 대상을 더하고 뺄 때 동일 비중으로 덮이지 않는다.
        weightsTouched: true,
      });
    },
    [effectiveSelection],
  );

  const handleTargetWeightChange = useCallback(
    (sectorId: string, weightTenths: number) => {
      if (effectiveSelection === null) return;
      commitWeights(
        Object.fromEntries(
          effectiveSelection.items.map((item) => [
            item.sectorId,
            item.sectorId === sectorId ? weightTenths : item.weightTenths,
          ]),
        ),
      );
    },
    [commitWeights, effectiveSelection],
  );

  const handleDistributeWeightsEven = useCallback(() => {
    if (effectiveSelection === null) return;
    // 표시 순서는 앞 단계가 정해 넘겼다. 그 순서로 나눠야 잔여분이 붙는 자리가 두 화면에서 같다.
    commitWeights(
      distributeEven(effectiveSelection.items.map((item) => item.sectorId)),
    );
  }, [commitWeights, effectiveSelection]);

  // --- 등록표 조회. 섹터 수를 알아야 유효성 제약을 미리 적용한 응답을 받는다 ----------------
  const [registryState, setRegistryState] = useState<RegistryViewState>({
    status: "loading",
  });
  const [registryReload, setRegistryReload] = useState(0);

  useEffect(() => {
    if (universeSize === null) return;
    const controller = new AbortController();
    let active = true;

    // 실패한 뒤의 다시 시도는 `loading`으로 되돌리지 않는다. 안내가 사라졌다 나타나면 alert가
    // 다시 읽히고 초점이 튄다(StrategyCardList의 상태 설명).
    setRegistryState((current) =>
      current.status === "failed"
        ? { ...current, retrying: true }
        : { status: "loading" },
    );

    fetchStrategyRegistry(universeSize, controller.signal)
      .then((registry) => {
        if (active) setRegistryState({ status: "loaded", registry });
      })
      .catch((error: unknown) => {
        if (!active) return;
        const normalized = normalizeStrategyRegistryError(error);
        if (normalized.canceled) return;
        setRegistryState((current) => ({
          status: "failed",
          message: normalized.message,
          failureCount:
            (current.status === "failed" ? current.failureCount : 0) + 1,
          retrying: false,
        }));
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [universeSize, registryReload]);

  const reloadRegistry = useCallback(
    () => setRegistryReload((count) => count + 1),
    [],
  );

  const registry =
    registryState.status === "loaded" ? registryState.registry : null;
  const settings = useStrategySettings(registry, universeSize);

  // --- 규칙·비용 버전(②). 조회 실패 시 분석을 막고 사용자가 다시 조회한다 -------------------
  const [versions, setVersions] = useState<AnalysisVersions | null | "loading">(
    "loading",
  );
  const [versionsReload, setVersionsReload] = useState(0);
  const [versionsRetrying, setVersionsRetrying] = useState(false);
  const versionsRetryRequested = useRef(false);
  const versionsRetryButtonRef = useRef<HTMLButtonElement>(null);
  const strategyCardsRef = useRef<HTMLElement>(null);

  /**
   * 버전 조회는 **지금 넘겨받은 조회 함수**를 따른다.
   *
   * <p>한때 화면이 열릴 때 한 번만 조회했다(T094). 부모가 다시 그릴 때마다 조회가 되풀이되며
   * '분석하기'가 잠깐씩 막히는 것을 없애려던 것인데, 그렇게 하면 호출자가 통로를 실제로 바꿨을 때도
   * 버전 조회만 옛 통로에 멈춘다. 평가 기간 판정과 요청 전송은 새 통로를 쓰는데 버전은 옛 통로의
   * 결과(예: 연결 전의 `null`)에 머물러 '분석하기'가 계속 막혔다.
   *
   * <p>그래서 두 가지를 갈라 푼다.
   * <ul>
   *   <li><b>무엇을 따를지는 조회 함수로 가른다.</b> 객체 참조가 아니라 함수 참조를 의존성으로 둔다.
   *       통로 객체를 매번 새로 펼쳐 만들어도 같은 함수를 담고 있으면 다시 조회하지 않는다.</li>
   *   <li><b>다시 조회하는 동안 받은 값을 거두지 않는다.</b> '확인하는 중'으로 되돌리지 않아 버튼이
   *       깜빡이지 않는다. 새 결과가 오면 그 값으로 바뀌고, 조회가 실패하면 그때 막힌다.</li>
   * </ul>
   *
   * <p><b>남는 비용</b>: 호출자가 렌더마다 조회 함수 자체를 새로 만들면 부모가 다시 그릴 때마다
   * 요청이 한 번씩 더 나간다. 화면은 깜빡이지 않지만 요청은 헛돈다. 통로를 안정된 참조로 넘기라는
   * 계약(`AnalysisSupport`)이 그 몫을 맡는다.
   *
   * <p><b>409 뒤에는 일부러 두 등록표를 다시 조회한다(`versionsReload`).</b> 003 등록표 버전이
   * 바뀌면 409 `REGISTRY_VERSION_CHANGED`로 거절된다. 004의 옛 계산 규칙 버전 거절은 Phase 10 T029
   * 구현 뒤 같은 복구 경로를 쓴다. 조회 실패 후 사용자 재시도에서도 '확인하는 중'으로 분석을 막는다.
   */
  const fetchAnalysisVersions = support.fetchAnalysisVersions;

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    fetchAnalysisVersions(controller.signal)
      .then((next) => {
        if (active) setVersions(next);
      })
      .catch(() => {
        if (active) setVersions(null);
      })
      .finally(() => {
        if (active) setVersionsRetrying(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [fetchAnalysisVersions, versionsReload]);

  useEffect(() => {
    if (
      !versionsRetryRequested.current ||
      versionsRetrying ||
      versions === "loading"
    )
      return;
    versionsRetryRequested.current = false;
    // 재시도로 사라지는 버튼에 초점을 남기지 않는다(NFR-001). 최초 조회에는 이동하지 않는다.
    if (versions === null) versionsRetryButtonRef.current?.focus();
    else strategyCardsRef.current?.focus();
  }, [versions, versionsRetrying]);

  // --- 공통 평가 기간(①). 섹터 집합과 등록표의 워밍업 상한이 바뀔 때만 조회한다 ---------------
  //
  // 값을 바꾸는 동안에는 받아 둔 거래일 목록으로 판정하므로 요청이 나가지 않는다(T098, research R7-1).
  const windowQuery = useMemo(() => {
    if (selection.status !== "ready" || !registry) return null;
    return evaluationWindowQuery(
      selection.selection.items.map((item) => item.sectorId),
      registry,
    );
  }, [selection, registry]);
  const {
    state: evaluationWindow,
    retry: retryEvaluationWindow,
    retrying: evaluationWindowRetrying,
  } = useEvaluationWindow(support.fetchEvaluationWindow, windowQuery);

  // --- 워밍업 판정(①). 카드 목록은 기본 설정 기준, 선택 전략은 현재 설정 기준 -----------------
  const minEvaluationYears = settings.ready
    ? settings.analysisCondition.minEvaluationYears
    : Number(registry?.analysisConditions.minEvaluationYears.default);

  const selectedStrategyId = settings.selectedStrategyId;
  const currentWarmup = settings.warmupTradingDays;

  const selectedJudgement = useMemo((): WarmupJudgement | null => {
    if (!settings.selectedStrategy || settings.warmupTradingDays === null)
      return null;
    return support.judgeWarmup({
      strategy: settings.selectedStrategy,
      evaluationWindow,
      warmupTradingDays: settings.warmupTradingDays,
      minEvaluationYears,
    });
  }, [
    support,
    evaluationWindow,
    settings.selectedStrategy,
    settings.warmupTradingDays,
    minEvaluationYears,
  ]);

  /**
   * 고른 전략의 평가 구간이 워밍업 때문에 줄었다는 안내의 재료(FR-011, SC-004). 줄지 않았으면 `null`이다.
   *
   * <p>시작일을 정한 후보는 001이 `instrumentId`로 알려 준다. 사용자가 읽을 섹터 이름은 002 선택과 섹터
   * 목록에서 찾고, 찾지 못하면 식별자를 그대로 보인다. 이름을 지어내지 않는다. 워밍업을 이루는 항목은
   * 등록표 `warmupRule.sum`이 가리키는 축의 라벨·단위와 지금 값이다(FR-005).
   *
   * <p><b>통로가 원인을 주지 않은 줄어든 판정(`reduction: null`)도 버리지 않는다.</b> 그때는 이 값이
   * `null`이고, '분석하기' 위의 안내가 판정 문장을 그대로 보인다(SC-004).
   */
  const reducedPeriod = useMemo((): ReducedPeriodInput | null => {
    const strategy = settings.selectedStrategy;
    if (
      !strategy ||
      !selectedJudgement ||
      selectedJudgement.verdict.state !== "reduced" ||
      !selectedJudgement.window ||
      !selectedJudgement.reduction ||
      settings.warmupTradingDays === null
    ) {
      return null;
    }
    const { bindingCandidate, bindingSector } = selectedJudgement.reduction;
    // 001이 섹터 코드를 함께 주면 그것을 쓰고, 주지 않으면 002 선택에서 종목 코드로 되짚는다.
    const sectorCode =
      bindingSector ??
      (selection.status === "ready"
        ? selection.selection.items.find(
            (candidate) => candidate.instrumentId === bindingCandidate,
          )?.sectorId
        : undefined);
    const sectorName =
      sectorCode !== undefined && catalog?.status === "loaded"
        ? catalog.catalog.sectors.find((sector) => sector.code === sectorCode)
            ?.name
        : undefined;
    // 값이 수가 아닌 항목(합성 값 등)은 워밍업에 더해질 수 없으므로 원인에서 뺀다. 이름을 지어 붙이지 않는다.
    const values = settings.values ?? {};
    const causes = (strategy.warmupRule?.sum ?? [])
      .filter((term): term is string => typeof term === "string")
      .flatMap((name): WarmupCause[] => {
        const axis = strategy.axes.find((candidate) => candidate.name === name);
        const value = values[name];
        return axis && typeof value === "number"
          ? [{ label: axis.label, value, unit: axis.unit }]
          : [];
      });
    return {
      window: selectedJudgement.window,
      reduction: selectedJudgement.reduction,
      bindingName: sectorName ?? bindingCandidate,
      warmupTradingDays: settings.warmupTradingDays,
      causes,
    };
  }, [
    settings.selectedStrategy,
    settings.values,
    settings.warmupTradingDays,
    selectedJudgement,
    selection,
    catalog,
  ]);

  /**
   * 카드 하나의 판정. **고른 카드만 지금 설정의 워밍업으로 판정한다**(FR-011).
   *
   * <p>다른 카드는 기본 설정 기준이다. 고른 카드까지 기본 설정으로 판정하면 관측 기간을 늘려 평가 구간이
   * 사라져도 카드는 여전히 고를 수 있다고 말하고, 그 아래 안내와 '분석하기'만 막혀 한 화면이 두 말을
   * 한다. 지금 워밍업을 계산할 수 없으면(비워 둔 축이 있으면) 기본 설정으로 대신 판정하지 않고 판정하지
   * 못했다는 사실(`unknown`)을 카드에 보인다 — 대신 판정하면 화면이 하지 않은 판정의 결과를 말한다(원칙 VI).
   *
   * <p><b>값을 만질 때 서버에 묻지 않는다.</b> 판정은 이미 받은 평가 기간으로 화면 안에서 한다 —
   * 값마다 서버를 오가면 늦게 온 응답이 이미 바뀐 값을 판정한다(research R7-1).
   */
  /** 카드 하나의 판정. 워밍업을 지금 계산할 수 없는 고른 카드는 `null`이다(아래 `judgeWarmup`이 `unknown`으로 읽는다) */
  const judgeCard = useCallback(
    (strategy: StrategyView) => {
      const selected = strategy.strategyId === selectedStrategyId;
      if (selected && currentWarmup === null) return null;
      return support.judgeWarmup({
        strategy,
        evaluationWindow,
        warmupTradingDays:
          selected && currentWarmup !== null
            ? currentWarmup
            : strategy.warmupTradingDays,
        minEvaluationYears,
      });
    },
    [
      support,
      evaluationWindow,
      minEvaluationYears,
      selectedStrategyId,
      currentWarmup,
    ],
  );

  const judgeWarmup = useCallback(
    (strategy: StrategyView): WarmupVerdict => {
      const judgement = judgeCard(strategy);
      // `unknown`은 고를 수 없다는 뜻이 아니다. 카드는 눌린 채 남고 복원하면 지금 설정으로 다시 판정한다.
      if (judgement === null) {
        return { state: "unknown", message: WARMUP_UNCOMPUTABLE };
      }
      // 'reduced'의 문장은 이제 아무도 그리지 않는다 — 카드는 고를 수 없는 사유만 적고, 왜 이 범위인지는
      // 분석 조건의 '평가할 수 있는 범위' 옆 도움말이 말한다(`S15P21E205-406`).
      return judgement.verdict;
    },
    [judgeCard],
  );

  /**
   * 카드에 마우스를 올리면 보일 평가 가능 범위(2026-10-01). 분석 기간 행 아래에 늘 적던 문장을 카드로 옮겼다 —
   * 범위는 전략마다 다르므로 견주는 자리에서 읽힌다. 판정과 같은 입력으로 구해 두 말이 갈리지 않는다.
   */
  const rangeOfCard = useCallback(
    (strategy: StrategyView) => judgeCard(strategy)?.window ?? null,
    [judgeCard],
  );

  /*
    카드의 사유는 카드 밖 한 곳, 화면 머리의 제목 아래에 모아 적는다(FR-011, 2026-10-01). 카드 목록과 그 칸이 같은
    결과를 나눠 써야 카드의 번호와 머리의 문장이 어긋나지 않는다.
  */
  const noticeIdBase = useId();
  const cardNotices = useMemo(() => {
    if (registryState.status !== "loaded") return [];
    const { strategies } = registryState.registry;
    return collectSharedNotices(
      strategies,
      strategies.map((strategy) => judgeWarmup(strategy)),
      noticeIdBase,
    );
  }, [registryState, judgeWarmup, noticeIdBase]);

  // --- 다시 확인할 보관값 -------------------------------------------------------------------
  //
  // **두 자리로 가른다.** 전략에 딸린 값은 그 전략을 고른 뒤에 보여야 뜻이 통하지만, 전략과
  // 무관한 값(분석 조건)은 고르기 전에 알려야 한다 — 최소 평가 기간은 카드의 선택 가능 여부를
  // 바꾸는 값이라(FR-011a) 카드를 보기 전에 그 값이 무엇인지 알아야 한다. 한 자리에 묶어 두면
  // 되돌아간 사실을 전략을 고른 뒤에야 알게 되고, 그것은 사용자 입장에서 조용히 바꾼 것과
  // 같다(FR-017).
  const globalInvalid = settings.invalidValues.filter(
    (entry) => entry.strategyId === null,
  );

  /**
   * 재검증이 비워 둔 시작 금액이 복원을 기다린다.
   *
   * 이 동안은 금액을 **모르는 것**으로 다룬다. 시작 금액이 비면 등록표 기본값이 쓰이므로, 여기서
   * 기본값을 보이거나 보내면 사용자가 정한 적 없는 금액으로 조용히 바뀐 것과 같다(FR-017).
   */
  const amountAwaitsRestore = globalInvalid.some(
    (entry) => entry.axisName === "initialAmount" && entry.restorable,
  );

  /** 복원 버튼 하나가 전략 축과 분석 조건을 모두 받는다. 되돌릴 자리가 어디인지 항목이 말한다. */
  const restoreInvalidValue = (entry: InvalidStoredValue) => {
    if (entry.axisName === null) return;
    if (entry.strategyId !== null)
      settings.resetAxis(entry.strategyId, entry.axisName);
    else if (entry.axisName === "initialAmount")
      settings.resetAnalysisCondition("initialAmount");
  };
  const strategyInvalid = settings.invalidValues.filter(
    (entry) =>
      entry.strategyId !== null &&
      entry.strategyId === settings.selectedStrategyId,
  );

  // --- 행동 가능 여부 ---------------------------------------------------------------------
  //
  // **"보낼 수 있는가"는 축이 다 찼는지 하나로만 묻는다.** 앞서는 "복원할 수 있는 안내가 있는가"로
  // 대신 물었는데, 그 둘이 같은 답을 내는 것은 재검증이 비운 축마다 안내를 남기기 때문이지 그렇게
  // 하라고 정해 둔 것이 아니다. 그 대응이 한 줄만 어긋나면 축이 빠진 요청이 서버로 나간다(T048의
  // "등록된 축이 빠져 있으면 400"). 안내를 그릴지는 위 두 목록이 따로 정한다.
  const settingsIncomplete =
    settings.selectedStrategyId !== null &&
    !settings.isComplete(settings.selectedStrategyId);

  /**
   * 고른 전략의 지금 값이 어기는 규칙(FR-008). 전략 설정 패널이 보이는 안내와 **같은 함수**로 판정한다.
   *
   * 값이 바뀔 때마다 다시 계산하며 서버에 묻지 않는다. 판정하지 못한 경우(`unjudged`)를 통과로 접지
   * 않는다 — 접수 시점에 서버가 같은 규칙으로 거절할 요청을 보내게 된다.
   */
  const ruleCheck = useMemo(() => {
    if (!settings.selectedStrategy || !settings.values || universeSize === null)
      return null;
    return checkRules(
      settings.selectedStrategy.validationRules,
      settings.values,
      {
        UNIVERSE_SIZE: universeSize,
      },
    );
  }, [settings.selectedStrategy, settings.values, universeSize]);
  const rulesBlocked =
    ruleCheck !== null &&
    (ruleCheck.unjudged || ruleCheck.violations.length > 0);

  /**
   * 고른 전략이 사용자가 정한 비중을 쓰는데 그 합계가 100%가 아니다(T091).
   *
   * <p><b>그 전략에서만 막는다.</b> 합계는 `targetWeightSource: USER`인 전략에만 뜻이 있고, 004
   * 계약이 그 전략의 목표 비중 합을 1로 요구한다. 자동 배분 전략은 이 비중을 읽지 않으므로 같은
   * 선택으로도 분석할 수 있다 — 앞 단계로 통째로 되돌리면 그 사용자까지 막는다. 전략 ID가 아니라
   * 등록표가 내려 준 출처로 가른다(FR-005).
   */
  const unbalancedUserWeights =
    selection.status === "ready" &&
    settings.selectedStrategy?.targetWeightSource === "USER" &&
    !effectiveWeights.isBalanced;

  /**
   * 요청에 실을 시작 금액. **화면에 보이는 값과 보내는 값이 같은 자리에서 나온다(T092).**
   *
   * 사용자가 바꾸지 않았으면 등록표의 기본값이다. 분석 조건 행과 요청 조립이 이 값 하나를 함께 읽어,
   * 한쪽만 바뀌어 보이는 금액과 보내는 금액이 갈라지는 일이 없게 한다. B10(T067)이 상태에 값을
   * 채우면 그 값이 여기로 온다.
   */
  const initialAmount =
    registry === null || amountAwaitsRestore
      ? null
      : (settings.analysisCondition.initialAmount ??
        registry.analysisConditions.initialAmount.default);

  /**
   * 분석 기간(FR-009, T067). **분석 조건 행·요청이 이 값 하나를 함께 읽는다.**
   *
   * <p>날짜를 골랐으면 그 기간이고, '최대 범위 선택'이면 평가 가능 범위 전체다. 아직 정하지 않았으면 `null`이고
   * 범위 전체로 대신 채우지 않는다(2026-10-01). 골랐으면 그 기간이다. 범위가 나중에 바뀌어(다른
   * 워밍업, 다른 전략) 고른 기간이 범위를 벗어나도 <b>잘라 맞추지 않는다.</b> 막고 되돌릴 길을 보인다.
   * 잘라 맞추면 사용자가 고르지 않은 기간으로 분석이 나간다(FR-017).
   */
  const evaluableRange = selectedJudgement?.window ?? null;

  /**
   * 011 사례 경로의 추가 제약(진입 계약 §3). `case`가 없으면 `null`이고 003은 지금과 같다.
   *
   * <p>거래일 셈은 001이 준 거래일 목록으로만 한다. 계산할 수 없으면 값을 지어내지 않고 그 사실을
   * `'uncomputable'`로 남겨 '분석하기'가 이유와 함께 막히게 한다.
   */
  const caseConstraint = useMemo(():
    CasePeriodConstraint | "uncomputable" | null => {
    if (caseId === null || caseId === "") return null;
    if (caseWindow.status !== "loaded") return "uncomputable";
    if (
      evaluationWindow.status !== "loaded" ||
      settings.warmupTradingDays === null
    )
      return "uncomputable";
    const built = buildCasePeriodConstraint({
      postWindow: caseWindow.postWindow,
      endMonth: caseWindow.endMonth,
      warmupTradingDays: settings.warmupTradingDays,
      tradingDays: evaluationWindow.window.tradingDaysFromBase,
    });
    return typeof built === "string" ? "uncomputable" : built;
  }, [caseId, caseWindow, evaluationWindow, settings.warmupTradingDays]);

  /** 공통 평가 가능 범위가 이 제약을 채우지 못하면 그 전략으로는 이 사례를 볼 수 없다. */
  const caseBlocker = ((): string | null => {
    if (caseId === null || caseId === "") return null;
    if (caseWindow.status === "unavailable") return CASE_WINDOW_UNAVAILABLE;
    if (caseWindow.status === "loading") return CASE_WINDOW_LOADING;
    if (caseConstraint === null) return null;
    if (caseConstraint === "uncomputable") return CASE_WINDOW_UNAVAILABLE;
    if (evaluableRange === null) return null;
    return rangeCoversCase(evaluableRange, caseConstraint)
      ? null
      : CASE_RANGE_UNCOVERABLE;
  })();

  const [casePeriodPrefilled, setCasePeriodPrefilled] = useState(false);
  const baseCaseConstraint =
    caseConstraint === "uncomputable" ? null : caseConstraint;
  const activeCaseConstraint =
    baseCaseConstraint !== null && casePeriodPrefilled
      ? {
          ...baseCaseConstraint,
          note: `${baseCaseConstraint.note} ${CASE_PERIOD_PREFILLED_NOTE}`,
        }
      : baseCaseConstraint;
  const { periodStart, periodEnd } = settings.analysisCondition;
  const chosenPeriod = useMemo(
    (): AnalysisPeriod | null =>
      periodStart !== undefined && periodEnd !== undefined
        ? { start: periodStart, end: periodEnd }
        : null,
    [periodStart, periodEnd],
  );
  const periodFull = settings.analysisCondition.periodFull === true;
  const periodMode: PeriodMode =
    chosenPeriod !== null ? "custom" : periodFull ? "full" : "unset";
  const period = chosenPeriod ?? (periodFull ? evaluableRange : null);

  /*
    '최대 범위 선택'을 누르기 직전의 기간. 되돌리기 아이콘이 이리로 돌아간다 — 날짜를 골라 두었으면 그 날짜, 아니면
    정하지 않은 상태다. 화면 안에서만 기억한다: 새로 고친 뒤에는 되돌릴 곳을 모르므로 정하지 않은 상태로 간다.
  */
  const periodBeforeFullRef = useRef<AnalysisPeriod | null>(null);
  const selectFullRange = useCallback(() => {
    periodBeforeFullRef.current = chosenPeriod;
    settings.setAnalysisCondition({ periodFull: true });
  }, [chosenPeriod, settings]);
  // 입력이 무효로 끝나 '최대 범위' 상태로 돌아갈 때는 되돌릴 곳을 새로 기억하지 않는다. 그 순간의 `chosenPeriod`는
  // 입력 도중에 잠깐 반영된 중간 날짜라, 기억하면 되돌리기 아이콘이 사용자가 고른 적 없는 기간으로 간다.
  const restoreFullRange = useCallback(() => {
    settings.setAnalysisCondition({ periodFull: true });
  }, [settings]);
  const undoFullRange = useCallback(() => {
    const before = periodBeforeFullRef.current;
    periodBeforeFullRef.current = null;
    if (before === null) settings.resetAnalysisCondition("period");
    else
      settings.setAnalysisCondition({
        periodStart: before.start,
        periodEnd: before.end,
      });
  }, [settings]);

  // 사례에서 들어왔고 아직 기간을 고르지 않았으면 사례 직후 구간을 포함하는 기간으로 미리 맞춘다(S15P21E205-450).
  // 한 사례에 한 번만 한다 — 사용자가 기간을 지우거나 바꾸면 그 선택을 다시 덮지 않는다.
  const prefilledCaseRef = useRef<string | null>(null);
  useEffect(() => {
    if (
      caseId === null ||
      baseCaseConstraint === null ||
      evaluableRange === null ||
      chosenPeriod !== null ||
      periodFull ||
      prefilledCaseRef.current === caseId
    )
      return;
    const next = defaultCasePeriod(
      baseCaseConstraint,
      evaluableRange,
      Number.isInteger(minEvaluationYears) ? minEvaluationYears : 0,
    );
    prefilledCaseRef.current = caseId;
    if (next === null) return;
    settings.setAnalysisCondition({ periodStart: next.start, periodEnd: next.end });
    setCasePeriodPrefilled(true);
  }, [caseId, baseCaseConstraint, evaluableRange, chosenPeriod, periodFull, minEvaluationYears, settings]);
  const periodOutOfRange =
    chosenPeriod !== null &&
    evaluableRange !== null &&
    !isWithin(chosenPeriod, evaluableRange);
  /**
   * 고른 기간이 최소 평가 기간보다 짧다(FR-011a). 범위 전체가 짧으면 카드 판정이 이미 막으므로, 여기서는
   * 사용자가 범위 안에서 기간을 좁힌 경우만 잡는다.
   *
   * <p><b>고르지 않은 기간(범위 전체)은 여기서 판정하지 않는다.</b> 그 판정은 001 산식을 따르는 카드 판정이
   * 맡는다. 여기서 달력 규약으로 한 번 더 보면 두 규약이 경계에서 갈려, 카드는 고를 수 있다는데 '분석하기'만
   * 막히고 되돌릴 기간도 없는 상태가 생긴다.
   *
   * <p>지금은 화면만 막는다. 004 요청 계약에 최소 평가 기간 자리가 없어 서버 재확인은 합의 뒤다(data-model
   * §10, "착수 전에 알아둘 것" ⑧).
   */
  const periodTooShort =
    chosenPeriod !== null &&
    Number.isInteger(minEvaluationYears) &&
    !meetsMinimumYears(chosenPeriod, minEvaluationYears);
  const minEvaluationYearsLabel =
    registry?.analysisConditions.minEvaluationYears.choices.find(
      (choice) => Number(choice.value) === minEvaluationYears,
    )?.label ?? String(minEvaluationYears);

  /** 고른 전략의 규칙 버전. 버전을 받지 못했거나 전략을 고르지 않았으면 `null`이다 */
  const rulesVersion = useMemo(() => {
    if (
      versions === "loading" ||
      versions === null ||
      !settings.selectedStrategy
    )
      return null;
    return resolveRulesVersion(versions, settings.selectedStrategy.strategyId);
  }, [versions, settings.selectedStrategy]);

  const prepared = useMemo(() => {
    if (
      selection.status !== "ready" ||
      registry === null ||
      initialAmount === null ||
      unbalancedUserWeights ||
      ruleCheck === null ||
      rulesBlocked ||
      !settings.ready ||
      settings.registryVersion === null ||
      !settings.selectedStrategy ||
      !settings.values ||
      !settings.isComplete(settings.selectedStrategy.strategyId) ||
      settings.warmupTradingDays === null ||
      !selectedJudgement?.window ||
      period === null ||
      periodOutOfRange ||
      periodTooShort ||
      versions === "loading" ||
      versions === null ||
      rulesVersion?.status !== "registered"
    ) {
      return null;
    }
    if (effectiveSelection === null) return null;
    return {
      selection: effectiveSelection,
      strategy: settings.selectedStrategy,
      parameters: settings.values,
      registryVersion: settings.registryVersion,
      warmupTradingDays: settings.warmupTradingDays,
      period,
      initialAmount,
      rulesVersion: rulesVersion.rulesVersion,
      costProfileVersion: versions.costProfileVersion,
    };
  }, [
    selection,
    effectiveSelection,
    registry,
    initialAmount,
    unbalancedUserWeights,
    ruleCheck,
    rulesBlocked,
    settings,
    selectedJudgement,
    period,
    periodOutOfRange,
    periodTooShort,
    versions,
    rulesVersion,
  ]);

  const [periodDraftInvalid, setPeriodDraftInvalid] = useState(false);
  const analyzeBlocker = ((): string | null => {
    if (registry === null || !settings.ready) return REGISTRY_NOT_LOADED;
    const strategy = settings.selectedStrategy;
    if (!strategy) return SELECT_STRATEGY_FIRST;
    if (!strategy.selectable)
      return strategy.unselectableReason ?? REASON_MISSING;
    if (settingsIncomplete || amountAwaitsRestore) return INVALID_STORED_VALUES;
    if (ruleCheck?.unjudged) return RULES_UNJUDGED;
    if (ruleCheck && ruleCheck.violations.length > 0) return RULES_VIOLATED;
    if (unbalancedUserWeights && selection.status === "ready") {
      return weightsUnbalancedMessage(effectiveWeights.totalTenths);
    }
    if (settings.warmupTradingDays === null) return WARMUP_UNCOMPUTABLE;
    if (!selectedJudgement) return EVALUATION_WINDOW_UNAVAILABLE;
    const verdict = selectedJudgement.verdict;
    if (verdict.state === "blocked" || verdict.state === "unknown")
      return verdict.message;
    if (!selectedJudgement.window) return EVALUATION_WINDOW_UNAVAILABLE;
    if (periodDraftInvalid) return PERIOD_DRAFT_INVALID;
    if (periodOutOfRange)
      return periodOutOfRangeMessage(selectedJudgement.window);
    if (periodTooShort) return periodTooShortMessage(minEvaluationYearsLabel);
    // 011 사례 경로의 제약(진입 계약 §3). 사례가 없으면 `caseBlocker`가 늘 `null`이라 지금과 같다.
    if (caseBlocker !== null) return caseBlocker;
    // 범위가 사례를 덮지 못하는 것처럼 고를 수 없는 사정이 먼저다. 그다음이 아직 고르지 않은 기간이다.
    if (period === null) return PERIOD_NOT_CHOSEN;
    if (
      activeCaseConstraint !== null &&
      period !== null &&
      (period.start > activeCaseConstraint.startMax ||
        period.end < activeCaseConstraint.endMin)
    ) {
      return activeCaseConstraint.note;
    }
    if (versions === "loading") return VERSIONS_LOADING;
    if (versions === null) return ANALYSIS_VERSIONS_UNAVAILABLE;
    if (rulesVersion?.status === "pendingDecision")
      return RULES_PENDING_DECISION;
    if (rulesVersion?.status !== "registered") return RULES_VERSION_UNAVAILABLE;
    return null;
  })();

  /**
   * 설정 조합 자동 비교로 넘어갈 수 있는가(FR-013).
   *
   * <p><b>'분석하기'보다 묻는 것이 적다.</b> 넘기는 것은 전략 하나와 섹터 후보 집합뿐이고, 설정값·
   * 평가 기간·분석 조건 버전은 007이 확인 화면에서 스스로 정하고 검증한다. 여기서 그것까지 물으면
   * 007이 판정할 일을 이 화면이 미리 막는 셈이 된다. 섹터 선택은 이 버튼이 그려지는 조건
   * (`selection.status === 'ready'`)이 이미 보장한다.
   */
  const autoCompareBlocker = ((): string | null => {
    if (registry === null || !settings.ready) return REGISTRY_NOT_LOADED;
    const strategy = settings.selectedStrategy;
    if (!strategy) return SELECT_STRATEGY_FIRST;
    if (!strategy.selectable)
      return strategy.unselectableReason ?? REASON_MISSING;
    return null;
  })();

  /**
   * 007 실행 전 확인 화면으로 넘어간다.
   *
   * <p><b>전략은 하나만 싣는다.</b> 007은 여러 전략을 받을 수 있지만 003은 방금 고른 전략 하나를
   * 넘기기로 정했다(FR-013, Clarifications). 질의 문자열 모양은 007 `ParamSearchPage`가 이미 읽는
   * `strategies`·`instruments` 그대로이며, 후보 숫자나 설정값은 싣지 않는다 — 탐색 범위는 007이
   * 등록표에서 정한다.
   */
  const handleAutoCompare = () => {
    if (
      autoCompareBlocker !== null ||
      selection.status !== "ready" ||
      !settings.selectedStrategy
    ) {
      return;
    }
    const instrumentIds = selection.selection.items.map((item) =>
      encodeURIComponent(item.instrumentId),
    );
    navigate(
      `/param-search?strategies=${encodeURIComponent(settings.selectedStrategy.strategyId)}` +
        `&instruments=${instrumentIds.join(",")}`,
    );
  };

  // --- 요청 조립과 전송 --------------------------------------------------------------------
  const [submission, setSubmission] = useState<Submission>({ status: "idle" });

  /**
   * 마지막 요청이 등록표 버전 변경(409)으로 거절되었다면 **그때 화면이 쓰던 등록표**다(research R9,
   * quickstart 10). 거절되지 않았으면 `null`이다.
   *
   * <p>참이면 등록표를 다시 받고 그 결과를 알린다. 다음 '분석하기'를 누를 때 거둔다 — 그 전에 거두면
   * 사용자는 왜 요청이 접수되지 않았는지 모른다.
   *
   * <p><b>옛 등록표를 함께 붙들어 두는 까닭</b>: 409 직후 첫 렌더에는 다시 조회가 아직 시작되지 않아 옛
   * 등록표가 '받음' 상태로 남아 있다. 받았는지만 보고 판정하면, 새 등록표를 보지도 않고 "그대로 두었습니다"를
   * 알린다. 그래서 옛 등록표와 **다른** 등록표를 받은 뒤에만 다시 받았다고 판정한다.
   */
  const [registryChange, setRegistryChange] = useState<{
    stale: RegistryResponse;
  } | null>(null);
  const registryChanged = registryChange !== null;
  const { clearSelection } = settings;

  /**
   * 지금 고른 전략. 요청을 보낸 뒤 사용자가 카드를 옮겼는지를 응답이 온 시점에 묻기 위한 것이다 —
   * `handleAnalyze`가 잡아 둔 값은 누른 시점의 것이라 그 사이의 선택을 모른다.
   */
  const selectedStrategyIdRef = useRef(settings.selectedStrategyId);
  useEffect(() => {
    selectedStrategyIdRef.current = settings.selectedStrategyId;
  }, [settings.selectedStrategyId]);

  /*
    고른 카드를 다시 누르면 선택을 내려놓는다(2026-10-01). 전략별로 바꿔 둔 값은 지우지 않아 다시 고르면 그대로
    돌아온다(`clearSelection`). 내려놓을 길이 없으면 전략을 고르지 않은 처음 화면으로 돌아갈 수 없다.
  */
  const handleSelect = useCallback(
    (strategyId: string) => {
      if (strategyId === settings.selectedStrategyId) settings.clearSelection();
      else settings.select(strategyId);
      setSubmission({ status: "idle" });
    },
    [settings],
  );

  const handleAnalyze = useCallback(async () => {
    if (
      analyzeBlocker !== null ||
      prepared === null ||
      submission.status === "sending"
    )
      return;

    // 값을 만들지 않는다. 시작 금액은 그 행에 보인 값을 그대로 싣는다.
    //
    // **조립도 실패를 알리는 길 안에 둔다.** 조립은 금액·비중이 계약 모양으로 옮겨지지 않으면 예외를
    // 던지는데, 앞서는 이것이 `try` 밖이라 거부된 프로미스로 버려졌다. 버튼을 눌러도 진행 표시도
    // 실패 안내도 없이 아무 일도 일어나지 않았다.
    //
    // 조립 실패는 서버가 한 말이 아니라 이 화면의 값이 계약에 맞지 않는다는 뜻이라, 예외 문장을
    // 그대로 보이지 않고 사용자가 할 수 있는 일을 말한다. 원인은 개발 도구에서 볼 수 있게 남긴다.
    let request;
    try {
      request = buildBacktestRequest({
        selection: prepared.selection,
        strategy: prepared.strategy,
        parameters: prepared.parameters,
        registryVersion: prepared.registryVersion,
        warmupTradingDays: prepared.warmupTradingDays,
        // 비용 버전은 화면에 보인 기본 비용 조건의 버전을 그대로 싣는다. 비워 보내면 조회와 접수 사이에 서버
        // 기본값이 바뀌었을 때 사용자가 본 적 없는 조건이 적용된다(004 결정, T105).
        versions: {
          rulesVersion: prepared.rulesVersion,
          costProfileVersion: prepared.costProfileVersion,
        },
        period: prepared.period,
        initialAmount: prepared.initialAmount,
        scenario: settings.analysisCondition.scenario,
        targetVolatility: settings.analysisCondition.targetVolatility,
      });
    } catch (error) {
      console.error("분석 요청을 조립하지 못했습니다.", error);
      setSubmission({ status: "failed", message: REQUEST_BUILD_FAILED });
      return;
    }

    setSubmission({ status: "sending" });
    setRegistryChange(null);
    try {
      const accepted = await support.createBacktest(request);
      setSubmission({ status: "accepted", accepted });
      /*
        011 사례 경로는 **접수되면 곧바로** 011 화면으로 간다(진입 계약 §2). 기준 실행의 진행 상태는
        011 화면이 005와 같은 방식으로 보이므로 완료를 여기서 기다리지 않는다.

        004 요청 본문에는 `case`를 싣지 않았다. 004 계약을 바꾸지 않고 화면이 주소로 들고 다닌다.

        **아래 결과 화면 이동보다 먼저 본다.** 사례를 들고 온 사용자가 보러 온 것은 결과 하나가 아니라
        사례와의 비교다. 순서를 뒤집으면 011 진입이 결과 화면에 먹혀 `case`가 주소에서 사라진다.
      */
      if (caseId !== null && caseId !== "" && accepted.publicToken) {
        void navigate(
          `/regime-link?${new URLSearchParams({ base: accepted.publicToken, case: caseId }).toString()}`,
        );
        return;
      }
      // 사례가 없으면 그 실행의 결과 주소로 옮긴다(003 T040이 005에 넘긴 자리, 005 FR-019).
      // 아직 계산 중이어도 결과 화면이 `QUEUED`·`RUNNING`을 폴링해 진행을 보이므로 여기서 기다리지 않는다.
      //
      // **토큰이 없으면 어느 쪽으로도 옮기지 않는다.** 공개 주소는 202 응답이 주는 값이고 화면이
      // 지어낼 수 없다(옛 접수 응답에는 없다). 그때는 접수 안내에 머물러 요청 번호를 남긴다.
      if (accepted.publicToken) {
        void navigate(`/results/${accepted.publicToken}`);
      }
    } catch (error) {
      // 거절마다 계약 §2가 정한 처리를 한다. **어느 경우에도 요청을 다시 보내지 않는다** — 다시 보낼지는
      // 사용자가 바뀐 내용을 보고 정한다.
      const serverMessage =
        error instanceof Error && error.message ? error.message : SUBMIT_FAILED;
      const rejection = readBacktestRejection(error);
      switch (rejection.code) {
        case BacktestRejectionCode.REGISTRY_VERSION_CHANGED:
          // 다시 받아 보관값을 새 등록표로 재검증한다(`useStrategySettings`가 등록표를 받을 때마다 한다).
          //
          // **004 규칙 등록표도 함께 다시 받는다.** 같은 409가 003 등록표 버전의 불일치에도, 004가 대체한 옛
          // 규칙 버전에도 오고 화면은 둘을 가를 수 없다(004 결정). 둘 다 받은 뒤에 사용자가 다시 누른다.
          setSubmission({ status: "idle" });
          if (registry !== null) setRegistryChange({ stale: registry });
          reloadRegistry();
          setVersions("loading");
          setVersionsReload((count) => count + 1);
          return;
        case BacktestRejectionCode.STRATEGY_NOT_REGISTERED:
          // 등록표는 늘 다시 받는다. 보낸 전략이 목록에서 빠졌는지는 사용자가 카드를 옮겼어도 보여야 한다.
          reloadRegistry();
          // **보낸 전략을 아직 고르고 있을 때만** 전략 선택으로 되돌리고 알린다. 보내는 동안 다른 카드를
          // 골랐다면 그 선택을 풀거나 옛 전략의 거절로 덮지 않는다 — 새로 고른 전략의 이야기가 아니다.
          // 전략별로 바꿔 둔 값은 어느 경우에도 지우지 않는다.
          if (selectedStrategyIdRef.current !== prepared.strategy.strategyId)
            return;
          clearSelection();
          setSubmission({
            status: "failed",
            message: STRATEGY_NOT_REGISTERED_MESSAGE,
          });
          return;
        case BacktestRejectionCode.UNREGISTERED_PARAMETER_VALUE:
          setSubmission({
            status: "failed",
            message: parameterRejectedMessage(rejection, prepared.strategy),
          });
          return;
        case BacktestRejectionCode.REGISTRY_UNAVAILABLE:
          // 서버 사정이라 같은 설정으로 다시 보낼 수 있다. 제안만 하고 보내는 것은 사용자가 누를 때다.
          setSubmission({
            status: "failed",
            message: serverMessage,
            retryable: true,
          });
          return;
        default:
          // 422 `VALIDATION_RULE_VIOLATED`는 서버가 규칙의 `detail`을 메시지로 싣는다. 그대로 보인다.
          setSubmission({ status: "failed", message: serverMessage });
      }
    }
  }, [
    analyzeBlocker,
    caseId,
    navigate,
    prepared,
    submission.status,
    settings.analysisCondition,
    support,
    reloadRegistry,
    clearSelection,
    registry,
  ]);

  // --- 화면 -----------------------------------------------------------------------------
  const selectedId = settings.selectedStrategyId;

  /** 등록표를 다시 받는 과정의 어디쯤인가. 받았어도 보관값 재검증을 마치기 전이면 아직 받는 중이다. */
  const registryReloadPhase: RegistryReloadPhase =
    registryState.status === "failed"
      ? registryState.retrying
        ? "reloading"
        : "failed"
      : registryState.status === "loaded" &&
          registryState.registry !== registryChange?.stale &&
          settings.ready &&
          versions !== "loading"
        ? "reloaded"
        : "reloading";

  /** 목표 비중 편집기의 줄이다. 이름은 섹터 목록에서, 비중은 지금 화면이 들고 있는 값에서 온다. */
  const targetWeights = useMemo((): TargetWeightRow[] | null => {
    if (effectiveSelection === null || catalog?.status !== "loaded") return null;
    const names = new Map(
      catalog.catalog.sectors.map((sector) => [sector.code, sector.name]),
    );
    return effectiveSelection.items.map((item) => ({
      sectorId: item.sectorId,
      name: names.get(item.sectorId) ?? item.sectorId,
      weightTenths: item.weightTenths,
    }));
  }, [effectiveSelection, catalog]);
  // 조회 중과 조회 실패를 가른다. 409 뒤 다시 받는 동안을 "확인할 수 없음"으로 보이면 곧 끝날 조회가 실패로
  // 읽히고, 같은 화면의 '분석하기' 사유("확인하는 중")와 두 말을 하게 된다.
  const costAssumption =
    versions === "loading"
      ? ("loading" as const)
      : versions !== null
        ? {
            version: versions.costProfileVersion,
            provisional: versions.costProfileProvisional,
            rates: versions.costRates,
          }
        : null;

  /*
    '분석하기' 원이 드러난 정도다. 전략을 고르기 전에는 숨고(0), 골랐지만 막혀 있으면 절반쯤 나오며(1), 누를 수
    있으면 다 드러난다(2). 투자 대상 고르기의 다음 단계 단추와 같은 셈법이라, 하나를 더 해야 열린다는 것이
    두 화면에서 같은 움직임으로 읽힌다. 보내는 중은 눌 수 없어도 다 드러난 채로 둔다 — 조건이 빠진 것이 아니다.
  */
  const analyzeBusy = submission.status === "sending";
  const analyzeBlocked = analyzeBlocker !== null || analyzeBusy;
  // 남은 것이 분석 기간을 고르는 일뿐이면 원은 다 드러나되 회색이고, 마우스를 올리면 무엇을 고를지 알린다
  // (2026-10-01). 다른 막힘은 고칠 것이 있다는 뜻이라 지금처럼 절반만 나온다.
  const analyzePending = analyzeBlocker === PERIOD_NOT_CHOSEN;
  const analyzeStage: StepOrbStage =
    !settings.selectedStrategy || selectedId === null
      ? 0
      : analyzeBlocker !== null && !analyzePending
        ? 1
        : 2;

  return (
    <div className={styles.page}>
      <AppHeader />
      <main
        className={styles.main}
        aria-labelledby="strategy-settings-title"
        // 머리 오른쪽 안내가 원의 단계를 따라 카드 줄 끝에 맞춘다(.lead).
        data-orb-stage={analyzeStage}
      >
        <header className={styles.intro}>
          {/*
            앞 단계로 돌아가는 길이다. 행동 버튼 줄에 두지 않는다 — 이 화면의 주된 행동은 '분석하기'
            하나이고(FR-002, 원칙 II) 뒤로 가기는 행동이 아니라 이동이다.

            브라우저 뒤로 가기가 아니라 경로를 직접 가리킨다. 배우기·시장 흐름에서 들어온 사용자는
            한 칸 뒤가 섹터 선택이 아니다. 사례 경로(011)로 들어왔으면 주소의 사례를 그대로 들고
            돌아가 기간 제약이 말없이 풀리지 않게 한다(진입 계약 §2).

            돌아갔다 와도 값은 남는다 — 섹터·비중과 전략 설정이 각자 탭 수명 저장소에 있다.
          */}
          <Link
            className={styles.backLink}
            to={
              caseId === null || caseId === ""
                ? SECTOR_SELECTION_PATH
                : buildSectorSelectionPath(caseId, null)
            }
          >
            <ArrowLeft size={15} aria-hidden="true" />
            투자 대상 고르기
          </Link>
          {/*
            투자 대상 고르기·배우기와 같은 머리: 제목은 왼쪽, 안내 문장은 오른쪽 인용 모양이다. 다만 두 칸을 위쪽에
            맞추고, 카드의 사유 목록은 제목 아래에 둔다 — 사유가 늘어도 제목이 아래로 밀리지 않고, 카드 줄 바로 아래가
            비어 고른 카드에서 상세 설정으로 곧장 이어진다.
          */}
          <div className={`${heading.hero} ${styles.introHero}`}>
            <div className={styles.introMain}>
              <div>
                <p className={heading.eyebrow} aria-hidden="true">STEP 02</p>
                <h1 id="strategy-settings-title" className={styles.title}>
                  <em className={heading.emphasis}>전략</em> 선택
                </h1>
              </div>
              {/* 카드가 서는 동안만 적는다. 앞 단계를 마치지 않았으면 가리킬 카드가 없다. */}
              <StrategyNoticeList
                notices={selection.status === "ready" ? cardNotices : []}
              />
            </div>
            <p className={styles.lead}>
              {/* 뜻이 나뉘는 자리에서 줄을 바꾼다. 줄마다 span 하나다. */}
              <span>고른 섹터로 어떤 전략을 돌려 볼지 고릅니다.</span>
              <span>그대로 바로 분석할 수 있습니다.</span>
            </p>
          </div>
        </header>

        {catalog === null ? (
          <p className={styles.loading} role="status">
            앞 단계에서 고른 섹터를 확인하는 중입니다.
          </p>
        ) : null}

        {/*
          섹터 목록을 받지 못하면 앞 단계의 선택이 온전한지 판정할 수 없다. 판정하지 않은 것을
          "마치지 않았다"로 접지 않고, 확인할 수 없다는 사실을 말한다(useSectorSelection의 상태 설명).
        */}
        {catalog !== null && catalog.status !== "loaded" ? (
          <NotReadyNotice
            className={styles.notice}
            message="섹터 목록을 확인할 수 없어 앞 단계의 선택을 읽지 못했습니다."
            action={
              catalog.status === "failed" && !catalog.retryable
                ? undefined
                : { label: "다시 불러오기", onAction: reloadCatalog }
            }
          />
        ) : null}

        {selection.status === "incomplete" ? (
          <NotReadyNotice
            className={styles.notice}
            message={
              // 대응표가 바뀐 경우는 "마치지 않았다"와 까닭이 달라, 같은 문장으로 알리면 사용자가
              // 이미 고른 섹터를 왜 다시 골라야 하는지 모른다.
              selection.problem === "mapVersionChanged"
                ? "섹터를 고른 뒤 섹터 대응표가 바뀌었습니다. 섹터·비중 단계에서 선택을 다시 확인해 주세요."
                : "섹터·비중 단계를 먼저 마쳐 주세요. 비교하려면 섹터를 두 개 이상 골라야 합니다."
            }
            action={{
              label: "섹터·비중 단계로 이동",
              onAction: () => navigate("/diagnosis/sectors"),
            }}
          />
        ) : null}

        {selection.status === "ready" ? (
          <>
            {selection.dropped.length > 0 ? (
              <p className={styles.restoreNotice} role="status">
                앞 단계에서 고른 섹터 {selection.dropped.length}개를 그대로 쓸
                수 없어 비교 대상에서 뺐습니다. 섹터·비중 단계에서 다시 확인해
                주세요.
              </p>
            ) : null}

            {/* 임시 등록표는 그 사실을 상시 표시하고 확정값으로 표현하지 않는다(계약 §1 `provisional`). */}
            {registry?.provisional ? (
              <p className={styles.provisional}>
                <ProvisionalBadge label="전략 설정 등록표" />
                <span>
                  이 전략 설정 등록표는 팀 확정 전 임시값입니다. 결과를
                  확정값으로 읽지 마세요.
                </span>
              </p>
            ) : null}

            {/*
              전략과 무관한 보관값의 안내다. **카드 줄보다 위에, 전략을 고르기 전에 보인다.**
              최소 평가 기간이 그렇다 — 그 값이 카드의 선택 가능 여부를 정하므로(FR-011a) 카드를
              보기 전에 지금 어떤 값이 쓰이고 있는지 알아야 한다.
            */}
            {globalInvalid.length > 0 ? (
              <InvalidValueList
                label="다시 확인할 분석 조건"
                entries={globalInvalid}
                onRestore={restoreInvalidValue}
              />
            ) : null}

            {/*
              위에서 고르고 아래에서 확인한다. **고른 전략의 상세를 카드 사이에 끼워 넣지 않는다** — 그러면
              고를 때마다 다른 카드가 밀려 견주던 자리를 잃는다. 카드 줄과 상세 카드는 오른쪽 칸의 원이 드러나는
              만큼 함께 좁아지지만(STEP 01과 같은 움직임), 카드 줄의 줄 수와 설정의 열 수는 원과 무관한 짜임 폭으로
              정해 고를 때마다 줄이 바뀌지 않는다.
            */}
            <div className={styles.layout}>
              <section
                ref={strategyCardsRef}
                className={styles.master}
                // 눈에 보이는 소제목은 두지 않는다(2026-10-01). 카드 줄이 무엇인지는 화면 제목 '전략 선택'이 이미
                // 말한다. 보조기술에는 같은 이름의 구역으로 알린다.
                aria-label="전략"
                tabIndex={-1}
              >

                {/*
                  평가 기간을 받지 못하면 카드마다 판정하지 못했다는 문장이 붙는다. 그 상태는 자료가 적재되는 중
                  (409 EVALUATION_WINDOW_NOT_READY)이거나 잠깐의 연결 오류일 수 있어 다시 불러 달라질 수 있으므로,
                  등록표 조회와 같이 다시 시도할 길을 둔다(FR-018과 같은 규약). 다시 받는 동안에는 안내를 거두지
                  않아 읽히는 문장이 깜빡이지 않는다.
                */}
                {evaluationWindow.status === "unavailable" ? (
                  <div className={styles.notice} role="status">
                    <p>
                      공통 평가 기간을 불러오지 못해 카드의 워밍업을 판정하지
                      못했습니다.
                    </p>
                    <button
                      type="button"
                      onClick={retryEvaluationWindow}
                      disabled={evaluationWindowRetrying}
                    >
                      {evaluationWindowRetrying
                        ? "다시 불러오는 중"
                        : "평가 기간 다시 불러오기"}
                    </button>
                  </div>
                ) : null}
                <StrategyCardList
                  state={registryState}
                  selectedStrategyId={selectedId}
                  onSelect={handleSelect}
                  onRetry={reloadRegistry}
                  judgeWarmup={judgeWarmup}
                  rangeOf={rangeOfCard}
                  notices={cardNotices}
                />
              </section>

              <section
                className={styles.detail}
                aria-labelledby="selected-strategy-title"
              >
                <div className={styles.detailBody}>
                  {/*
                    제목은 '상세 설정'으로 고정이다(2026-09-30). 제목이 전략마다 바뀌면 이 카드가 무엇을 하는
                    자리인지가 이름에 묻힌다. 고른 전략의 이름은 제목 앞에 강조색으로 두어 '동일 비중 상세 설정'으로
                    읽히게 하고(2026-10-01), 설명은 바로 위 고른 카드가 이미
                    보이므로 되풀이하지 않는다. 제목을 카드 안에 두어 카드 줄과 이 카드 사이 틈이 비고, 분석하기
                    원이 그 틈에 걸친다.

                    머리 오른쪽 끝에는 '분석하기'가 막힌 이유를 둔다. 원이 카드 오른쪽 위 모서리에 걸쳐 있어 바로
                    그 왼쪽이다. 원이 `aria-describedby`로 이 문장을 가리키므로 초점을 받은 원이 까닭을 함께
                    읽힌다. 스스로 알리지는 않는다: 같은 문장을 카드 안 안내가 이미 알린다.
                  */}
                  <div className={styles.detailHead}>
                    {settings.selectedStrategy ? (
                      <strong className={styles.detailStrategy}>
                        {settings.selectedStrategy.name}
                      </strong>
                    ) : null}
                    <h2 id="selected-strategy-title" className={styles.detailTitle}>
                      상세 설정
                    </h2>
                    <p id={ANALYZE_REASON_ID} className={styles.detailReason}>
                      {analyzeBlocker ?? ""}
                    </p>
                  </div>

                  {/*
                    기본값은 편의용 출발점이라는 말(FR-004)과 자동 배분 전략의 출력 비중 설명(FR-012)은 두 열
                    위에 둔다. 분석 조건의 기본값에도 해당하고, 한쪽 열에만 두면 두 열의 행 선이 어긋난다.
                    도움말로 내리지 않는다(frontend/CLAUDE.md).
                  */}
                  {registry !== null && settings.ready ? (
                    <div className={styles.detailNotes}>
                      <p className={styles.detailNote}>
                        기본값은 편의용 출발점이며 최적값이나 투자 권고가
                        아닙니다.
                      </p>
                      {settings.selectedStrategy?.targetWeightSource ===
                      "COMPUTED" ? (
                        <p className={styles.detailNote}>
                          출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과
                          다릅니다.
                        </p>
                      ) : null}
                    </div>
                  ) : null}

                  {settings.ready &&
                  settings.selectedStrategy &&
                  selectedId !== null ? (
                    <>
                      {unbalancedUserWeights ? (
                        <div className={styles.weightNotice} role="status">
                          <p>
                            {weightsUnbalancedMessage(
                              effectiveWeights.totalTenths,
                            )}
                          </p>
                          {/*
                            맞추는 자리가 같은 화면 안에 있으므로 다른 화면으로 보내지 않는다.
                            누르면 고른 대상 전체가 같은 비중으로 되돌아간다.
                          */}
                          <button
                            type="button"
                            onClick={handleDistributeWeightsEven}
                          >
                            동일하게 나누기
                          </button>
                        </div>
                      ) : null}

                      {strategyInvalid.length > 0 ? (
                        <InvalidValueList
                          label="다시 확인할 설정값"
                          entries={strategyInvalid}
                          onRestore={restoreInvalidValue}
                        />
                      ) : null}

                      {selectedJudgement &&
                      (selectedJudgement.verdict.state === "blocked" ||
                        selectedJudgement.verdict.state === "unknown") ? (
                        <p
                          className={styles.judgement}
                          role="status"
                          data-state={selectedJudgement.verdict.state}
                        >
                          {selectedJudgement.verdict.message}
                        </p>
                      ) : null}

                      {periodOutOfRange || periodTooShort ? (
                        <div className={styles.weightNotice} role="status">
                          <p>
                            {periodOutOfRange && evaluableRange
                              ? periodOutOfRangeMessage(evaluableRange)
                              : periodTooShortMessage(minEvaluationYearsLabel)}
                          </p>
                          {chosenPeriod !== null ? (
                            <button type="button" onClick={selectFullRange}>
                              최대 범위 선택
                            </button>
                          ) : null}
                        </div>
                      ) : null}
                    </>
                  ) : null}

                  {/*
                    분석 조건은 **한 자리에만 둔다.** 항목마다 한 행이 지금 값을 늘 보인다(FR-002). 요약 줄을 따로
                    두면 같은 값이 화면 두 곳에서 각각 말해진다.

                    **전략을 고르기 전에도 닿는다**(T102). 최소 평가 기간은 카드의 선택 가능 여부를 바꾸는 값이라,
                    그 값이 모든 카드를 막으면 선택에 묶인 입력으로는 되돌릴 길이 없다(FR-011a). 기간은 고른
                    전략의 평가 가능 범위가 있어야 정할 수 있으므로 그 전에는 막고 까닭을 적는다.

                    분석 조건 | 전략 설정 두 열이다. 오른쪽 열은 전략을 고르기 전에도 자리를 지켜, 고르는 순간
                    분석 조건이 옆으로 옮겨 가지 않게 한다. 좁으면 한 열로 쌓는다.
                  */}
                  <div className={styles.settingsGrid}>
                  {registry !== null && settings.ready ? (
                    <AnalysisConditionsPanel
                      rangeReason={
                        reducedPeriod === null ? undefined : (
                          <InlineHelp label="평가할 수 있는 범위" on="hover">
                            {describeReducedCause(reducedPeriod)}
                          </InlineHelp>
                        )
                      }
                      sectorCount={universeSize ?? 0}
                      conditions={registry.analysisConditions}
                      range={evaluableRange}
                      rangeUnavailableMessage={
                        settings.selectedStrategy
                          ? undefined
                          : RANGE_NEEDS_STRATEGY
                      }
                      period={period}
                      periodMode={periodMode}
                      onSelectFullRange={selectFullRange}
                      onRestoreFullRange={restoreFullRange}
                      onUndoFullRange={undoFullRange}
                      onPeriodDraftInvalid={setPeriodDraftInvalid}
                      caseConstraint={activeCaseConstraint}
                      initialAmount={initialAmount ?? undefined}
                      minEvaluationYears={minEvaluationYears}
                      targetVolatility={
                        settings.analysisCondition.targetVolatility
                      }
                      cost={costAssumption}
                      onChange={(patch) => {
                        // 금액이 등록표 기본값과 같아지면 값을 담지 않고 "없음"으로 둔다. 입력이 무효가 되어
                        // 입력칸이 보이던 기본값으로 되돌릴 때도 이 길로 온다. 기본값을 사용자 값으로 담으면
                        // "없으면 기본값"이 깨져, 등록표 기본값이 바뀌어도 옛 기본값이 사용자가 고른 것처럼 남는다.
                        if (
                          patch.initialAmount ===
                          registry.analysisConditions.initialAmount.default
                        ) {
                          const rest = { ...patch };
                          delete rest.initialAmount;
                          settings.resetAnalysisCondition("initialAmount");
                          if (Object.keys(rest).length > 0)
                            settings.setAnalysisCondition(rest);
                          return;
                        }
                        settings.setAnalysisCondition(patch);
                      }}
                      onReset={settings.resetAnalysisCondition}
                    />
                  ) : null}

                  <div className={styles.strategyTrack}>
                  {settings.ready &&
                  settings.selectedStrategy &&
                  selectedId !== null &&
                  settings.values !== null &&
                  universeSize !== null ? (
                    <DetailSettingsPanel
                      strategy={settings.selectedStrategy}
                      values={settings.values}
                      universeSize={universeSize}
                      targetWeights={targetWeights}
                      onTargetWeightChange={handleTargetWeightChange}
                      onDistributeWeightsEven={handleDistributeWeightsEven}
                      evaluationRange={evaluableRange}
                      onChange={(axisName, value) =>
                        settings.setValue(selectedId, axisName, value)
                      }
                      onResetAxis={(axisName) =>
                        settings.resetAxis(selectedId, axisName)
                      }
                      onResetAll={() => settings.resetAll(selectedId)}
                    />
                  ) : (
                    <p className={styles.empty}>
                      위에서 전략을 고르면 여기에 전략 설정이 나타납니다.
                    </p>
                  )}

                  {/*
                    우회로 '설정 조합 자동 비교'. 고른 전략의 설정 조합을 찾는 길이라 전략 설정 아래에 둔다(FR-013).
                    '분석하기' 단추 자체는 카드 밖 원이다.
                  */}
                  <ActionButtons
                    autoCompare={{
                      disabled: autoCompareBlocker !== null,
                      reason: autoCompareBlocker ?? undefined,
                    }}
                    onAutoCompare={handleAutoCompare}
                  />
                  </div>
                  </div>
                </div>

              </section>

              {/*
                마지막 걸음 '분석하기'. 전략 카드 줄과 상세 설정 카드 사이 틈에 걸친 원으로, 투자 대상 고르기의
                STEP 02 단추와 같은 자리·모양·움직임이다. 화면에서 실행 단추가 나오는 자리는 여기 하나뿐이다.

                막힌 이유는 원 바로 왼쪽(상세 설정 카드 머리 줄)에 한 문장으로 두고 `aria-describedby`로 잇는다. 원에서
                멀리 떨어진 카드 아래에 두면 절반만 나온 원을 본 사용자가 이유를 찾아 내려가야 한다. 원이 카드 아래로
                내려가는 좁은 화면에서는 원 칸 안의 사본이 대신 보인다. 사본은 보조기술에 숨긴다: 원이 가리키는
                문장은 머리 쪽 하나다. `disabled` 대신 `aria-disabled`를 쓰는 까닭은 ActionButtons와 같다(NFR-001).
                보내는 중에는 이름이 바뀌고 다시 눌리지 않으며, 원은 다 드러난 채 글자를 보인다.
              */}
              <StepOrbButton
                className={styles.analyzeDock}
                stage={analyzeStage}
                pendingHint={analyzePending ? PERIOD_NOT_CHOSEN : undefined}
                step="STEP 03"
                label={analyzeBusy ? "보내는 중" : "분석하기"}
                aria-label={analyzeBusy ? "분석 요청을 보내는 중" : "분석하기"}
                aria-disabled={analyzeBlocked}
                aria-busy={analyzeBusy}
                aria-describedby={
                  analyzeBlocker !== null ? ANALYZE_REASON_ID : undefined
                }
                data-action="analyze"
                onClick={() => {
                  if (!analyzeBlocked) void handleAnalyze();
                }}
              >
                <p className={styles.analyzeStatus} aria-hidden="true">
                  {analyzeBlocker ?? ""}
                </p>
              </StepOrbButton>
            </div>

            {versions === null || versionsRetrying ? (
              <div
                className={styles.notice}
                role="status"
                aria-label="분석 조건 조회 상태"
              >
                <p>
                  {versionsRetrying
                    ? "분석 조건을 다시 불러오는 중입니다."
                    : "분석 조건을 불러오지 못했습니다. 다시 불러온 뒤 분석할 수 있습니다."}
                </p>
                <button
                  ref={versionsRetryButtonRef}
                  type="button"
                  disabled={versionsRetrying}
                  onClick={() => {
                    versionsRetryRequested.current = true;
                    setVersionsRetrying(true);
                    setVersions("loading");
                    setVersionsReload((count) => count + 1);
                  }}
                >
                  분석 조건 다시 불러오기
                </button>
              </div>
            ) : null}
            {registryChanged ? (
              <RegistryChangedNotice
                phase={registryReloadPhase}
                pendingRestoreCount={
                  [...globalInvalid, ...strategyInvalid].filter(
                    (entry) => entry.restorable,
                  ).length
                }
                removedMessages={settings.invalidValues
                  .filter(
                    (entry) =>
                      entry.strategyId !== null && entry.axisName === null,
                  )
                  .map((entry) => entry.message)}
              />
            ) : null}
            {submission.status === "accepted" ? (
              <p className={styles.result} role="status">
                분석 요청을 접수했습니다. 요청 번호{" "}
                {submission.accepted.backtestId}, 상태{" "}
                {statusText[submission.accepted.status] ??
                  submission.accepted.status}
                . 결과 주소를 받지 못해 결과 화면으로 옮기지 못했습니다. 내
                기록에서 이 요청 번호로 찾을 수 있습니다.
              </p>
            ) : null}
            {submission.status === "failed" ? (
              <div className={styles.error} role="alert">
                <p className={styles.errorMessage}>{submission.message}</p>
                {submission.retryable ? (
                  <button
                    type="button"
                    className={styles.restoreButton}
                    disabled={analyzeBlocker !== null}
                    onClick={() => {
                      void handleAnalyze();
                    }}
                  >
                    다시 시도
                  </button>
                ) : null}
              </div>
            ) : null}
          </>
        ) : null}
      </main>
    </div>
  );
}
