import { RotateCcw } from "lucide-react";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { DateField } from "@/components/common/DateField";
import { InlineHelp } from "@/components/common/InlineHelp";
import { ProvisionalBadge } from "@/components/paramsearch/ProvisionalBadge";
import type {
  AnalysisCondition,
  RestorableCondition,
} from "@/hooks/useStrategySettings";
import type { AnalysisConditions } from "@/types/strategyRegistry";
import {
  checkPeriodInput,
  isIsoDate,
  isWithin,
  type AnalysisPeriod,
} from "@/utils/analysisPeriod";
import { buildAmountQuickAdds } from "./amountQuickAdd";
import type { CasePeriodConstraint } from "./casePeriodConstraint";
import styles from "./AnalysisConditionsPanel.module.css";
import fieldStyles from "./AxisField.module.css";
import type { CostRates } from "./analysisSupport";
import { AxisNumberField } from "./AxisNumberField";
import {
  InlineChoices,
  SettingGroup,
  SettingRow,
  SettingSelect,
} from "./SettingRow";

/** 화면에 보일 비용 조건. 004가 등록한 현재 버전의 것이다. */
export type CostAssumptionView = {
  version: string;
  provisional: boolean;
  rates: CostRates;
};

export type AnalysisConditionPatch = Partial<
  Omit<AnalysisCondition, "scenario">
>;

interface AnalysisConditionsPanelProps {
  /** 등록표 `analysisConditions`. 금액 범위와 최소 평가 기간 후보를 여기서 읽는다 */
  conditions: AnalysisConditions;
  /** 워밍업을 채운 뒤 평가할 수 있는 공통 범위. 아직 모르면 `null`이고 기간을 바꿀 수 없다 */
  range: AnalysisPeriod | null;
  /** `range`가 없을 때 그 까닭. 없으면 "아직 확인하지 못했다"는 일반 문장을 쓴다 */
  rangeUnavailableMessage?: string;
  /** 지금 쓰이는 분석 기간. '최대 범위 선택'이면 `range`와 같고, 아직 정하지 않았으면 `null`이다 */
  period: AnalysisPeriod | null;
  /** 분석 기간의 상태. 정하지 않았으면 `period`가 `null`이다 */
  periodMode: PeriodMode;
  /** '최대 범위 선택'. 평가 가능 범위 전체를 쓴다 */
  onSelectFullRange: () => void;
  /**
   * '최대 범위 선택' 상태로 돌아간다. 단추를 누른 것이 아니라, 그 상태에서 시작한 날짜 입력이 무효로 끝나 되돌릴
   * 때 쓴다. 되돌리기 아이콘이 기억하는 '누르기 직전 상태'를 건드리지 않는다 — 건드리면 입력 도중에 잠깐 반영된
   * 중간 날짜가 그 자리를 차지한다
   */
  onRestoreFullRange: () => void;
  /** '최대 범위 선택'을 누르기 직전 상태로 되돌린다 */
  onUndoFullRange: () => void;
  /**
   * 날짜 칸에 반영하지 않은 무효 날짜가 남아 있는지 알린다. 칸이 무효인 동안 '분석하기'를 막는 데 쓴다 — 막지
   * 않으면 칸에는 고친 날짜가 보이는데 요청은 그 전 기간으로 나간다(2026-10-01)
   */
  onPeriodDraftInvalid?: (invalid: boolean) => void;
  /**
   * 011 사례 경로에서만 오는 추가 제약(011 진입 계약 §3). 주소에 `case`가 없으면 `null`이고 동작이
   * 지금과 같다. 있으면 시작일에 상한이, 종료일에 하한이 더 걸리고 고정 문구를 보인다
   */
  caseConstraint?: CasePeriodConstraint | null;
  /** 002가 확정한 섹터 수. 비교 대상 행에 읽기 전용으로 보인다 */
  sectorCount: number;
  /** 지금 쓰이는 시작 금액. 재검증이 비워 두고 복원을 기다리면 `undefined`다 */
  initialAmount: number | undefined;
  /**
   * 평가할 수 있는 범위가 왜 그만큼인지 알려 주는 도움말. 워밍업이 앞을 깎았을 때만 온다. '분석 기간' 이름 옆에
   * 두어 행을 펼치지 않아도 닿는다(SC-004)
   */
  rangeReason?: ReactNode;
  minEvaluationYears: number;
  /** 변동성 제한의 목표 변동성(연, 퍼센트 숫자). 끈 상태면 `undefined`다 */
  targetVolatility?: number;
  /**
   * 비용 조건. 조회하는 중이면 `'loading'`, 확인하지 못했으면 `null`이다. 일반 문구로 대신하지 않고, 조회 중을
   * 확인 불가로 접지 않는다
   */
  cost: CostAssumptionView | "loading" | null;
  /** 허용된 값일 때만 불린다. 받는 쪽(`setAnalysisCondition`)이 같은 잣대로 한 번 더 거른다 */
  onChange: (patch: AnalysisConditionPatch) => void;
  onReset: (name: RestorableCondition) => void;
}

export const COST_UNAVAILABLE =
  "비용 조건을 확인할 수 없습니다. 확인하기 전에는 분석을 시작할 수 없습니다.";
/** 비용 수치가 어디서 온 값인지. 사용자가 고칠 수 있는 값이 아니라는 사실을 문구가 대신 말한다(FR-010). */
const COST_BASIS = "2026년 한국 ETF 거래 적용 기준과 동일";

export const COST_LOADING = "비용 조건을 확인하는 중입니다.";

/** 모르는 값을 채우지 않는다. 확인하지 못한 항목은 그 사실을 적는다(원칙 IV). */
const UNAVAILABLE = "확인할 수 없음";

export const TARGET_RISK_HELP = (
  <>
    <p>
      포트폴리오가 흔들리는 정도(변동성)가 정한 값보다 커지면, 투자 비중을 줄이고 줄인 만큼을
      현금으로 두는 규칙입니다.
    </p>
    <p>
      매달 말 최근 3개월(63거래일)의 흔들림을 보고 조절합니다. 예를 들어 목표가 연 10%인데 최근
      흔들림이 20%면 절반만 투자하고 절반은 현금으로 둡니다. 흔들림이 목표보다 작으면 그대로 두며,
      빚을 내서 더 사지는 않습니다.
    </p>
    <p>
      급락할 때 손실을 줄일 수 있지만, 현금으로 빼 둔 사이 시장이 반등하면 그만큼 수익을 놓칠 수
      있습니다.
    </p>
  </>
);

/** 등록표 범위(min~max, step 간격)의 후보. 목록으로만 두어 범위 밖 값이 생기지 않는다. */
function rangeChoices({ min, max, step }: { min: number; max: number; step: number }): number[] {
  const count = Math.round((max - min) / step);
  return Array.from({ length: count + 1 }, (_, i) => Number((min + i * step).toFixed(4)));
}
const RANGE_UNAVAILABLE =
  "평가할 수 있는 범위를 아직 확인하지 못해 기간을 바꿀 수 없습니다.";

/** 기간이 아직 정해지지 않았을 때의 값. 범위를 모르거나, 사용자가 아직 고르지 않았다. 고장이 아니라 정해지기 전이다. */
const PERIOD_UNSET = "정해지지 않음";

function toNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Number(value))
  ) {
    return Number(value);
  }
  return null;
}

function formatBps(value: number): string {
  return `${value.toLocaleString("ko-KR", { maximumFractionDigits: 4 })}bp`;
}

/**
 * 분석 기간이 지금 어느 상태인가(2026-10-01). `unset`은 아직 정하지 않음, `full`은 '최대 범위 선택'으로 평가 가능
 * 범위 전체를 따름, `custom`은 사용자가 날짜를 직접 고름이다.
 */
export type PeriodMode = "unset" | "full" | "custom";

interface PeriodFieldProps {
  range: AnalysisPeriod | null;
  rangeUnavailableMessage: string;
  period: AnalysisPeriod | null;
  periodMode: PeriodMode;
  caseConstraint: CasePeriodConstraint | null;
  onChange: (period: AnalysisPeriod) => void;
  /** '최대 범위 선택'. 평가 가능 범위 전체를 쓰고, 범위가 바뀌면 그 범위를 따른다 */
  onSelectFull: () => void;
  /** '최대 범위 선택' 상태로 돌아간다. 그 상태에서 시작한 입력이 무효로 끝났을 때 쓴다(`onRestoreFullRange`) */
  onRestoreFull: () => void;
  /** '최대 범위 선택'을 누르기 직전 상태로 되돌린다. 단추 옆 되돌리기 아이콘이 부른다 */
  onUndoFull: () => void;
  /** 기간을 정하지 않은 상태로 되돌린다. 아무것도 정하지 않은 데서 시작한 입력이 무효가 되었을 때 쓴다 */
  onClear: () => void;
  onDraftInvalid?: (invalid: boolean) => void;
}

/** 사례 제약을 어겼는가. 어겼으면 고정 문구, 아니면 `null`이다(011 진입 계약 §3). */
function checkCaseConstraint(
  draft: AnalysisPeriod,
  constraint: CasePeriodConstraint | null,
): string | null {
  if (constraint === null) return null;
  if (!isIsoDate(draft.start) || !isIsoDate(draft.end)) return null;
  if (draft.start > constraint.startMax || draft.end < constraint.endMin)
    return constraint.note;
  return null;
}

/**
 * 분석 기간(FR-009). '분석 기간' 행을 펼치면 나오는 폼이라 제목은 따로 두지 않는다.
 *
 * <p><b>범위 밖 날짜는 반영하지 않는다.</b> 달력의 `min`·`max`는 고르는 것을 돕기만 하고 직접 친 날짜를
 * 막지 못하므로, 바뀔 때마다 `checkPeriodInput`으로 다시 본다. 입력칸에는 사용자가 넣은 날짜를 그대로
 * 남기고 이유를 보인다 — 경계로 잘라 넣으면 사용자가 고르지 않은 날짜로 분석이 나간다.
 *
 * <p><b>입력 도중의 날짜가 남지 않게 한다.</b> 날짜 칸은 한 글자마다 바뀜을 보내므로 `31`을 치는 도중의
 * `03`처럼 범위 안인 중간 날짜가 먼저 반영된다. 마지막 날짜가 무효가 되면 입력을 시작하기 전의 기간으로
 * 되돌린다(`AxisNumberField`와 같은 규약). 되돌리지 않으면 입력칸은 "저장하지 않았습니다"라고 말하는데
 * 요청은 사용자가 의도한 적 없는 중간 날짜로 나간다.
 */
function PeriodField({
  range,
  rangeUnavailableMessage,
  period,
  periodMode,
  caseConstraint,
  onChange,
  onSelectFull,
  onRestoreFull,
  onUndoFull,
  onClear,
  onDraftInvalid,
}: PeriodFieldProps) {
  const startId = useId();
  const endId = useId();
  const messageId = useId();
  const [draft, setDraft] = useState<AnalysisPeriod>(
    period ?? { start: "", end: "" },
  );
  const [synced, setSynced] = useState(period);
  /** 입력을 시작할 때의 기간과 그때의 상태. 입력이 무효가 되면 이리로 되돌린다 */
  const atEditStart = useRef<{
    period: AnalysisPeriod | null;
    mode: PeriodMode;
  }>({
    period,
    mode: periodMode,
  });

  // 바깥에서 기간이 바뀌면('최대 범위 선택'과 그 되돌리기, 전략·워밍업이 바뀌어 범위가 달라짐) 입력칸을 맞춘다.
  if (period?.start !== synced?.start || period?.end !== synced?.end) {
    setSynced(period);
    setDraft(period ?? { start: "", end: "" });
  }

  /** 범위 검사에 사례 제약을 겹친다. 되돌리기 판정도 같은 잣대를 써야 두 길이 갈리지 않는다. */
  const check = (next: AnalysisPeriod): string | null =>
    range === null
      ? rangeUnavailableMessage
      : (checkPeriodInput(next.start, next.end, range) ??
        checkCaseConstraint(next, caseConstraint));

  const draftIsCurrent =
    draft.start === period?.start && draft.end === period?.end;
  // 아직 정하지 않았고 칸도 비어 있으면 고칠 것이 없다. 무엇을 정해야 하는지는 '분석하기'가 알린다.
  const untouched = period === null && draft.start === "" && draft.end === "";
  const problem =
    range === null
      ? rangeUnavailableMessage
      : untouched
        ? null
        : draftIsCurrent &&
          period !== null &&
          isWithin(period, range) &&
          checkCaseConstraint(period, caseConstraint) === null
          ? null
          : check(draft);

  // 어느 칸이 문제인지 가른다. 순서가 뒤집힌 경우만 둘 다 문제다.
  const badDate = (date: string) =>
    range !== null &&
    (!isIsoDate(date) || date < range.start || date > range.end);
  const reversed =
    isIsoDate(draft.start) && isIsoDate(draft.end) && draft.start > draft.end;
  const startInvalid =
    problem !== null && range !== null && (badDate(draft.start) || reversed);
  const endInvalid =
    problem !== null && range !== null && (badDate(draft.end) || reversed);

  const rememberEditStart = () => {
    atEditStart.current = { period, mode: periodMode };
  };

  const update = (next: AnalysisPeriod) => {
    setDraft(next);
    if (range === null) return;
    if (check(next) !== null) {
      // 되돌린 값이 바깥에서 돌아와도 입력칸의 글은 사용자가 친 그대로 둔다. 그래서 `synced`를 먼저 맞춘다.
      const before = atEditStart.current;
      if (before.mode === "custom" && before.period) {
        if (
          before.period.start !== period?.start ||
          before.period.end !== period?.end
        ) {
          setSynced(before.period);
          onChange(before.period);
        }
      } else if (before.mode === "full") {
        if (periodMode !== "full") {
          setSynced(range);
          onRestoreFull();
        }
      } else if (periodMode !== "unset") {
        // 정하지 않은 상태에서 시작했으면 되돌릴 곳도 정하지 않은 상태다. 범위 전체로 대신 채우지 않는다.
        setSynced(null);
        onClear();
      }
      return;
    }
    if (next.start === period?.start && next.end === period?.end) return;
    onChange(next);
  };

  /*
    달력의 `min`·`max`는 고르는 것을 돕기만 한다. 직접 친 날짜는 위 검사가 다시 거른다.
  */
  const rangeStartMax =
    caseConstraint && range
      ? caseConstraint.startMax < range.end
        ? caseConstraint.startMax
        : range.end
      : range?.end;
  const rangeEndMin =
    caseConstraint && range
      ? caseConstraint.endMin > range.start
        ? caseConstraint.endMin
        : range.start
      : range?.start;

  /*
    서로를 잠근다. 시작일 달력은 이미 고른 종료일 뒤를 열지 않고, 종료일 달력은 시작일 앞을
    열지 않는다. 뒤집힌 기간을 달력으로는 만들 수 없게 되어, 그 경우의 안내를 읽을 일이 없다.
    직접 쳐 넣는 길은 남아 있으므로 위 `checkPeriodInput` 검사는 그대로 둔다.
  */
  const startMax =
    isIsoDate(draft.end) && (!rangeStartMax || draft.end < rangeStartMax)
      ? draft.end
      : rangeStartMax;
  const endMin =
    isIsoDate(draft.start) && (!rangeEndMin || draft.start > rangeEndMin)
      ? draft.start
      : rangeEndMin;

  // 칸에 반영하지 않은 무효 날짜가 남았는지 알린다. 칸이 사라지면(행을 접음) 무효 날짜도 함께 사라진다. 칸이
  // 저장된 기간 그대로인데 범위가 줄어 밖으로 밀려난 것은 여기서 알리지 않는다 — 화면이 그 사유를 따로 말한다.
  const draftInvalid = problem !== null && range !== null && !draftIsCurrent;
  useEffect(() => {
    onDraftInvalid?.(draftInvalid);
  }, [draftInvalid, onDraftInvalid]);
  useEffect(() => () => onDraftInvalid?.(false), [onDraftInvalid]);

  return (
    <fieldset className={fieldStyles.field} aria-label="분석 기간">
      <div className={fieldStyles.inputRow}>
        <label htmlFor={startId} className={styles.dateLabel}>
          시작일
        </label>
        <DateField
          id={startId}
          label="시작일"
          className={fieldStyles.input}
          min={range?.start}
          max={startMax}
          value={draft.start}
          disabled={range === null}
          aria-invalid={startInvalid ? "true" : "false"}
          aria-describedby={messageId}
          onFocus={rememberEditStart}
          onChange={(next) => update({ ...draft, start: next })}
        />
        <label htmlFor={endId} className={styles.dateLabel}>
          종료일
        </label>
        <DateField
          id={endId}
          label="종료일"
          className={fieldStyles.input}
          min={endMin}
          max={range?.end}
          value={draft.end}
          disabled={range === null}
          aria-invalid={endInvalid ? "true" : "false"}
          aria-describedby={messageId}
          onFocus={rememberEditStart}
          onChange={(next) => update({ ...draft, end: next })}
        />
        {/*
          '최대 범위 선택'(2026-10-01). 예전에는 범위 전체가 저절로 기간이 되고 그 범위를 문장으로 적었는데, 사용자가
          고르지 않은 기간으로 분석이 나갔다. 이제 범위는 단추가 품고, 누르면 범위 전체를 쓴다. 누른 뒤에는 다시 누를
          일이 없어 막고, 누르기 직전 상태로 돌아가는 아이콘을 옆에 둔다. 날짜를 직접 고른 동안에는 단추가 열려 있다.
        */}
        <button
          type="button"
          className={fieldStyles.quickAddButton}
          disabled={range === null || periodMode === "full"}
          aria-pressed={periodMode === "full"}
          title={range ? `${range.start} ~ ${range.end}` : undefined}
          onClick={onSelectFull}
        >
          최대 범위 선택
        </button>
        {periodMode === "full" ? (
          <button
            type="button"
            className={styles.undoFull}
            aria-label="최대 범위 선택 되돌리기"
            title="최대 범위 선택 되돌리기"
            onClick={onUndoFull}
          >
            <RotateCcw size={12} strokeWidth={2.2} aria-hidden="true" />
          </button>
        ) : null}
      </div>
      <p
        id={messageId}
        className={problem && range ? fieldStyles.problem : fieldStyles.hint}
        aria-live="polite"
      >
        {/* 문제가 없으면 비워 둔다. 범위는 '최대 범위 선택' 단추가 품고, 행 아래 설명이 적는다. */}
        {/* 무엇이 잘못됐는지만 짧게 적는다. 반영하지 않았다는 사실은 막힌 '분석하기'가 함께 말한다. */}
        {problem && range ? problem : range ? "" : rangeUnavailableMessage}
      </p>
      {/* 사례 경로의 고정 문구다. 어겼을 때만이 아니라 늘 보인다 — 왜 좁아졌는지가 먼저다. */}
      {caseConstraint ? (
        <p className={fieldStyles.hint}>{caseConstraint.note}</p>
      ) : null}
    </fieldset>
  );
}

/**
 * 분석 조건(US3, FR-002·FR-009·FR-010·FR-011a).
 *
 * <p><b>항목마다 한 행이고, 행의 값이 곧 요약이다.</b> 예전에는 요약 한 줄과 '분석 조건' 아코디언이
 * 따로 있어 열면 같은 값이 두 번 보였고, 바꿀 수 있는 넷과 읽기만 하는 둘이 한 덩이에 섞여 있었다
 * (2026-09-30). 지금은 분석 기간·비교 대상·시작 금액·비용 조건이 행의 값으로 늘 보인다(FR-002). 후보가
 * 셋뿐인 최소 평가 기간과 켜고 끄는 변동성 제한은 행 오른쪽에서 바로 고르고, 날짜와 금액만 값을 눌러 그
 * 자리에서 편다 — 허용 범위와 입력 문제를 함께 읽어야 하는 입력이기 때문이다(SC-005, 2026-09-30 개정).
 *
 * <p>전략을 고르기 전에도 그린다 — 최소 평가 기간이 카드를 막을 수 있어서다(T102).
 *
 * <p><b>바꿀 수 있는 것은 넷뿐이다.</b> 분석 기간(평가 가능 범위 안), 시작 금액(등록표 범위 안), 최소 평가
 * 기간(등록표 후보 가운데), 변동성 제한(끔 또는 등록표 범위 안의 목표 변동성). 비용 가정은 004가 등록한
 * 수치·버전·임시 여부를 <b>읽기 전용</b>으로만 보이고, 운용 방식은 연속 운용으로 고정해 보인다. 수수료·세금을
 * 고치거나 수익이 높아지는 쪽으로 조건을 골라 주는 선택지를 두지 않는다(FR-010, 원칙 IV). 변동성 제한은
 * 004의 '목표 위험 맞춤'(`TARGET_RISK`) 시나리오다.
 *
 * <p><b>값을 모른다.</b> 범위·후보·라벨·단위는 모두 props로 받은 등록표와 판정에서 읽는다(FR-005).
 */
export function AnalysisConditionsPanel({
  conditions,
  range,
  rangeUnavailableMessage = RANGE_UNAVAILABLE,
  period,
  periodMode,
  onSelectFullRange,
  onRestoreFullRange,
  onUndoFullRange,
  onPeriodDraftInvalid,
  caseConstraint = null,
  sectorCount,
  initialAmount,
  minEvaluationYears,
  targetVolatility,
  cost,
  rangeReason,
  onChange,
  onReset,
}: AnalysisConditionsPanelProps) {
  const volatilityId = useId();
  const amount = conditions.initialAmount;
  const volatility = conditions.targetVolatility;
  const years = conditions.minEvaluationYears;

  // 평가 가능 범위는 행 아래에 적지 않는다(2026-10-01). 범위는 전략 카드에 마우스를 올리면 보이고, 날짜 칸 옆
  // '최대 범위 선택'이 그 범위를 품는다.
  //
  // 범위가 없으면 기간을 고칠 수 없다. 펼칠 폼이 없으니 읽기 전용 행으로 두고 까닭을 행 아래에 늘 보인다(FR-003).
  const periodUnavailableCaption = range ? undefined : (
    <>
      <span className={styles.captionLine}>{rangeUnavailableMessage}</span>
      {caseConstraint ? (
        <span className={styles.captionLine}>{caseConstraint.note}</span>
      ) : null}
    </>
  );

  const costRates =
    cost !== "loading" && cost ? (
      <span className={styles.rates}>
        <span>수수료 {formatBps(cost.rates.commissionBps)}</span>
        <span>호가 차이 {formatBps(cost.rates.spreadBps)}</span>
        <span>슬리피지 {formatBps(cost.rates.slippageBps)}</span>
        <span>세금 {formatBps(cost.rates.taxBps)}</span>
      </span>
    ) : null;

  return (
    <SettingGroup title="분석 조건">
      <SettingRow
        label="분석 기간"
        help={range ? rangeReason : undefined}
        value={period ? `${period.start} ~ ${period.end}` : PERIOD_UNSET}
        caption={range ? undefined : periodUnavailableCaption}
      >
        {range ? (
          <PeriodField
            range={range}
            rangeUnavailableMessage={rangeUnavailableMessage}
            period={period}
            periodMode={periodMode}
            caseConstraint={caseConstraint}
            onChange={(next) =>
              onChange({ periodStart: next.start, periodEnd: next.end })
            }
            onSelectFull={onSelectFullRange}
            onRestoreFull={onRestoreFullRange}
            onUndoFull={onUndoFullRange}
            onClear={() => onReset("period")}
            onDraftInvalid={onPeriodDraftInvalid}
          />
        ) : null}
      </SettingRow>

      <SettingRow
        label="시작 금액"
        value={
          initialAmount === undefined
            ? UNAVAILABLE
            : `${initialAmount.toLocaleString("ko-KR")}${amount.unit ?? ""}`
        }
        modified={initialAmount !== amount.default}
        onReset={() => onReset("initialAmount")}
      >
        <AxisNumberField
          label="시작 금액"
          unit={amount.unit}
          range={amount}
          defaultValue={amount.default}
          value={initialAmount}
          groupDigits
          roundToStep
          rangeHint={false}
          quickAdd={buildAmountQuickAdds(
            amount.step,
            amount.max - amount.min,
          )}
          onChange={(next) => onChange({ initialAmount: next })}
        />
      </SettingRow>

      <SettingRow
        label="최소 평가 기간"
        help={
          <InlineHelp label="최소 평가 기간" on="hover">
            워밍업을 채운 뒤 남는 평가 기간이 이보다 짧으면 그 전략으로 분석하지 않습니다.
          </InlineHelp>
        }
        modified={minEvaluationYears !== toNumber(years.default)}
        onReset={() => {
          const fallback = toNumber(years.default);
          if (fallback !== null) onChange({ minEvaluationYears: fallback });
        }}
        control={
          <InlineChoices
            label="최소 평가 기간"
            choices={years.choices.flatMap((choice) => {
              const value = toNumber(choice.value);
              if (value === null) return [];
              return [
                {
                  key: String(choice.value),
                  label: choice.label,
                  checked: value === minEvaluationYears,
                  onSelect: () => onChange({ minEvaluationYears: value }),
                },
              ];
            })}
          />
        }
      />

      <SettingRow
        label="변동성 제한"
        help={
          <InlineHelp label="변동성 제한" on="hover">
            {TARGET_RISK_HELP}
          </InlineHelp>
        }
        modified={targetVolatility !== undefined}
        onReset={() => onChange({ targetVolatility: undefined })}
        control={
          <>
            <InlineChoices
              label="변동성 제한"
              choices={[
                {
                  key: "off",
                  label: "쓰지 않음",
                  checked: targetVolatility === undefined,
                  onSelect: () => onChange({ targetVolatility: undefined }),
                },
                {
                  key: "on",
                  label: "씀",
                  checked: targetVolatility !== undefined,
                  onSelect: () => onChange({ targetVolatility: volatility.default }),
                },
              ]}
            />
            {targetVolatility !== undefined ? (
              <SettingSelect
                id={volatilityId}
                aria-label="목표 변동성(연)"
                value={targetVolatility}
                onChange={(event) =>
                  onChange({ targetVolatility: Number(event.target.value) })
                }
              >
                {rangeChoices(volatility).map((value) => (
                  <option key={value} value={value}>
                    연 {value}%
                  </option>
                ))}
              </SettingSelect>
            ) : null}
          </>
        }
      />

      {/* 002가 정한 값이라 여기서 고치지 않는다. */}
      <SettingRow label="비교 대상" value={`섹터 ${sectorCount}개`} />

      {/* 004의 다른 시나리오가 소유한다. 이 화면에서 바꾸지 않는다. */}
      <SettingRow label="운용 방식" value="연속 운용" />

      {/*
        비용 가정은 읽기 전용이다(FR-010). 버전과 임시 여부를 값으로, 수치는 그 아래 한 줄로 늘 보인다 —
        펼쳐야 보이게 하면 고칠 수도 없는 값을 보려고 한 번 더 눌러야 한다.
      */}
      <div role="group" aria-label="비용 가정">
        <SettingRow
          label="비용 가정"
          help={
            <InlineHelp label="비용 가정" on="hover">
              {/*
                비용 수치는 행 아래가 아니라 이 도움말 안, 기준 문장 다음 줄에 작게 둔다(2026-10-01). 행에는 버전과
                임시 여부만 남아 읽기 전용 값이라는 것이 한눈에 보이고, 수치는 물음표 하나로 닿는다(FR-010).
              */}
              <span className={styles.costBasis}>{COST_BASIS}</span>
              {costRates}
            </InlineHelp>
          }
          value={
            cost === "loading" ? (
              <span role="status">{COST_LOADING}</span>
            ) : cost ? (
              <>
                {cost.version}
                {cost.provisional ? (
                  <ProvisionalBadge label="비용 가정" />
                ) : null}
              </>
            ) : (
              UNAVAILABLE
            )
          }
          caption={
            cost === null ? (
              <span className={fieldStyles.problem} role="status">
                {COST_UNAVAILABLE}
              </span>
            ) : cost !== "loading" && cost.provisional ? (
              <span className={styles.provisionalNote}>
                팀 확정 전 임시 비용입니다. 결과를 확정된 비용 기준으로 읽지 마세요.
              </span>
            ) : undefined
          }
        />
      </div>
    </SettingGroup>
  );
}
