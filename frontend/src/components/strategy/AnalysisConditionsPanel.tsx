import { ChevronDown } from "lucide-react";
import { useId, useRef, useState, type ReactNode } from "react";
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
  /** 지금 쓰이는 분석 기간. 사용자가 고르지 않았으면 `range`와 같다 */
  period: AnalysisPeriod | null;
  /** 사용자가 기간을 직접 골랐는가. 참일 때만 '범위 전체로 되돌리기'를 보인다 */
  periodChosen: boolean;
  /**
   * 011 사례 경로에서만 오는 추가 제약(011 진입 계약 §3). 주소에 `case`가 없으면 `null`이고 동작이
   * 지금과 같다. 있으면 시작일에 상한이, 종료일에 하한이 더 걸리고 고정 문구를 보인다
   */
  caseConstraint?: CasePeriodConstraint | null;
  /** 지금 쓰이는 시작 금액. 재검증이 비워 두고 복원을 기다리면 `undefined`다 */
  initialAmount: number | undefined;
  /** 접혀 있을 때도 보이는 조건 요약(FR-002). 머리줄에 실린다 */
  summary?: ReactNode;
  /** 평가할 수 있는 범위 옆에 붙는 도움말. 워밍업이 앞을 깎았을 때만 온다 */
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
const COST_BASIS = "2026년 한국 ETF 거래에 적용되는 기준과 같습니다.";

export const COST_LOADING = "비용 조건을 확인하는 중입니다.";

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

interface PeriodFieldProps {
  range: AnalysisPeriod | null;
  rangeUnavailableMessage: string;
  period: AnalysisPeriod | null;
  periodChosen: boolean;
  /** 범위가 왜 그만큼인지 알려 주는 도움말. 워밍업이 앞을 깎았을 때만 온다 */
  rangeReason?: ReactNode;
  caseConstraint: CasePeriodConstraint | null;
  onChange: (period: AnalysisPeriod) => void;
  onReset: () => void;
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
 * 분석 기간(FR-009).
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
  rangeReason,
  range,
  rangeUnavailableMessage,
  period,
  periodChosen,
  caseConstraint,
  onChange,
  onReset,
}: PeriodFieldProps) {
  const startId = useId();
  const endId = useId();
  const messageId = useId();
  const [draft, setDraft] = useState<AnalysisPeriod>(
    period ?? { start: "", end: "" },
  );
  const [synced, setSynced] = useState(period);
  /** 입력을 시작할 때의 기간과, 그것이 사용자가 고른 기간이었는지 */
  const atEditStart = useRef<{
    period: AnalysisPeriod | null;
    chosen: boolean;
  }>({
    period,
    chosen: periodChosen,
  });

  // 바깥에서 기간이 바뀌면(범위 전체로 되돌리기, 전략·워밍업이 바뀌어 범위가 달라짐) 입력칸을 맞춘다.
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
  const problem =
    range === null
      ? rangeUnavailableMessage
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
    atEditStart.current = { period, chosen: periodChosen };
  };

  const update = (next: AnalysisPeriod) => {
    setDraft(next);
    if (range === null) return;
    if (check(next) !== null) {
      const before = atEditStart.current;
      if (before.chosen && before.period) {
        if (
          before.period.start !== period?.start ||
          before.period.end !== period?.end
        ) {
          // 되돌린 값이 바깥에서 돌아와도 입력칸의 글은 사용자가 친 그대로 둔다.
          setSynced(before.period);
          onChange(before.period);
        }
      } else if (periodChosen) {
        // 고르지 않은 상태에서 시작했으면 되돌릴 곳은 범위 전체다.
        setSynced(range);
        onReset();
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

  const hint = range
    ? `평가할 수 있는 범위는 ${range.start} ~ ${range.end}입니다.`
    : rangeUnavailableMessage;

  return (
    <fieldset className={fieldStyles.field}>
      <legend className={fieldStyles.header}>
        <span className={fieldStyles.label}>분석 기간</span>
      </legend>
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
        {periodChosen ? (
          <button
            type="button"
            className={fieldStyles.reset}
            onClick={() => {
              // 이미 범위 전체와 같은 날짜를 골라 두었으면 바깥 값이 바뀌지 않아 입력칸이 맞춰지지 않는다.
              if (range) setDraft(range);
              onReset();
            }}
          >
            범위 전체로 되돌리기
          </button>
        ) : null}
      </div>
      <p
        id={messageId}
        className={problem && range ? fieldStyles.problem : fieldStyles.hint}
        aria-live="polite"
      >
        {problem && range
          ? `${problem} 이 기간은 저장하지 않았습니다. ${hint}`
          : hint}
        {/* 범위가 왜 그만큼인지. 워밍업이 앞을 깎았을 때만 온다 */}
        {range ? rangeReason : null}
      </p>
      {/* 사례 경로의 고정 문구다. 어겼을 때만이 아니라 늘 보인다 — 왜 좁아졌는지가 먼저다. */}
      {caseConstraint ? (
        <p className={fieldStyles.hint}>{caseConstraint.note}</p>
      ) : null}
    </fieldset>
  );
}

/**
 * 분석 조건(US3, FR-009·FR-010·FR-011a).
 *
 * <p><b>접힌 채로 시작하고 열면 그 자리가 편집 폼이다.</b> 기본 화면의 입력은 전략 선택 하나이고(SC-005),
 * 지금 적용되는 조건은 머리줄의 요약이 늘 보인다(FR-002). 요약과 '조건 바꾸기'를 따로 두었더니 한 조건이
 * 화면 세 곳(제목·요약·여는 버튼)에서 말해졌다 — 2026-09-22에 하나로 합쳤다(`S15P21E205-406`).
 * 전략을 고르기 전에도 그린다 — 최소 평가 기간이 카드를 막을 수 있어서다(T102).
 *
 * <p><b>바꿀 수 있는 것은 넷뿐이다.</b> 분석 기간(평가 가능 범위 안), 시작 금액(등록표 범위 안), 최소 평가
 * 기간(등록표 후보 가운데), 변동성 제한(끔 또는 등록표 범위 안의 목표 변동성). 비용 가정은 004가 등록한 수치·버전·임시 여부를 <b>읽기 전용</b>으로만 보이고,
 * 운용 방식은 연속 운용으로 고정해 보인다. 수수료·세금을 고치거나 수익이 높아지는 쪽으로 조건을 골라 주는
 * 선택지를 두지 않는다(FR-010, 원칙 IV). 변동성 제한은 004의 '목표 위험 맞춤'(`TARGET_RISK`) 시나리오다.
 *
 * <p><b>값을 모른다.</b> 범위·후보·라벨·단위는 모두 props로 받은 등록표와 판정에서 읽는다(FR-005).
 */
export function AnalysisConditionsPanel({
  conditions,
  range,
  rangeUnavailableMessage = RANGE_UNAVAILABLE,
  period,
  periodChosen,
  caseConstraint = null,
  initialAmount,
  minEvaluationYears,
  targetVolatility,
  cost,
  summary,
  rangeReason,
  onChange,
  onReset,
}: AnalysisConditionsPanelProps) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const yearsGroup = useId();
  const volatilityGroup = useId();
  const volatilityId = useId();
  const amount = conditions.initialAmount;
  const volatility = conditions.targetVolatility;
  const years = conditions.minEvaluationYears;

  return (
    <section className={styles.panel} aria-label="분석 조건">
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={open ? bodyId : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        <span>분석 조건</span>
        <span className={styles.toggleEnd}>
          {/* 접혀 있을 때만 여는 자리임을 알린다(S15P21E205-451). 버튼 이름에는 넣지 않는다. */}
          {open ? null : (
            <span className={styles.toggleHint} aria-hidden="true">
              직접 선택해보세요
            </span>
          )}
          <ChevronDown
            className={styles.toggleIcon}
            size={16}
            strokeWidth={1.8}
            aria-hidden="true"
          />
        </span>
      </button>
      {summary}

      {open ? (
        <div id={bodyId} className={styles.body}>
          <PeriodField
            rangeReason={rangeReason}
            range={range}
            rangeUnavailableMessage={rangeUnavailableMessage}
            period={period}
            periodChosen={periodChosen}
            caseConstraint={caseConstraint}
            onChange={(next) =>
              onChange({ periodStart: next.start, periodEnd: next.end })
            }
            onReset={() => onReset("period")}
          />

          <AxisNumberField
            label="시작 금액"
            unit={amount.unit}
            range={amount}
            defaultValue={amount.default}
            value={initialAmount}
            groupDigits
            roundToStep
            quickAdd={buildAmountQuickAdds(
              amount.step,
              amount.max - amount.min,
            )}
            onChange={(next) => onChange({ initialAmount: next })}
            onReset={() => onReset("initialAmount")}
          />

          <fieldset className={fieldStyles.field}>
            <legend className={fieldStyles.header}>
              <span className={fieldStyles.label}>최소 평가 기간</span>
            </legend>
            <p className={fieldStyles.description}>
              워밍업을 채운 뒤 남는 평가 기간이 이보다 짧으면 그 전략으로
              분석하지 않습니다.
            </p>
            <div className={fieldStyles.choices}>
              {years.choices.map((choice) => {
                const value = toNumber(choice.value);
                if (value === null) return null;
                return (
                  <label
                    key={String(choice.value)}
                    className={fieldStyles.choice}
                  >
                    <input
                      type="radio"
                      name={yearsGroup}
                      checked={value === minEvaluationYears}
                      onChange={() => onChange({ minEvaluationYears: value })}
                    />
                    {choice.label}
                  </label>
                );
              })}
            </div>
          </fieldset>

          <fieldset className={fieldStyles.field}>
            <legend className={fieldStyles.header}>
              <span className={fieldStyles.label}>
                변동성 제한
                <InlineHelp label="변동성 제한" on="hover">
                  {TARGET_RISK_HELP}
                </InlineHelp>
              </span>
            </legend>
            <div className={fieldStyles.choices}>
              <label className={fieldStyles.choice}>
                <input
                  type="radio"
                  name={volatilityGroup}
                  checked={targetVolatility === undefined}
                  onChange={() => onChange({ targetVolatility: undefined })}
                />
                쓰지 않음
              </label>
              <label className={fieldStyles.choice}>
                <input
                  type="radio"
                  name={volatilityGroup}
                  checked={targetVolatility !== undefined}
                  onChange={() => onChange({ targetVolatility: volatility.default })}
                />
                씀
              </label>
            </div>
            {targetVolatility !== undefined ? (
              <div className={fieldStyles.inputRow}>
                <label htmlFor={volatilityId} className={styles.dateLabel}>
                  목표 변동성(연)
                </label>
                <select
                  id={volatilityId}
                  className={fieldStyles.input}
                  value={targetVolatility}
                  onChange={(event) =>
                    onChange({ targetVolatility: Number(event.target.value) })
                  }
                >
                  {rangeChoices(volatility).map((value) => (
                    <option key={value} value={value}>
                      {value}%
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            <p className={fieldStyles.hint}>
              {targetVolatility === undefined
                ? "흔들림과 상관없이 전략의 비중대로 계속 투자합니다."
                : "값이 낮을수록 더 자주, 더 많이 현금으로 옮깁니다."}
            </p>
          </fieldset>

          <div className={fieldStyles.field}>
            <span className={fieldStyles.label}>운용 방식</span>
            <p className={styles.readOnly}>
              연속 운용 (이 화면에서 바꾸지 않습니다)
            </p>
          </div>

          <div
            className={fieldStyles.field}
            role="group"
            aria-label="비용 가정"
          >
            <span className={fieldStyles.label}>
              비용 가정
              <InlineHelp label="비용 가정" on="hover">
                {COST_BASIS}
              </InlineHelp>
            </span>
            {cost === "loading" ? (
              <p className={styles.readOnly} role="status">
                {COST_LOADING}
              </p>
            ) : cost ? (
              <p className={styles.readOnly}>
                <span>수수료 {formatBps(cost.rates.commissionBps)}</span>
                <span>호가 차이 {formatBps(cost.rates.spreadBps)}</span>
                <span>슬리피지 {formatBps(cost.rates.slippageBps)}</span>
                <span>세금 {formatBps(cost.rates.taxBps)}</span>
                <span>
                  버전 {cost.version}
                  {cost.provisional ? (
                    <ProvisionalBadge label="비용 가정" />
                  ) : null}
                </span>
              </p>
            ) : (
              <p className={fieldStyles.problem} role="status">
                {COST_UNAVAILABLE}
              </p>
            )}
            {cost !== "loading" && cost?.provisional ? (
              <p className={fieldStyles.hint}>
                팀 확정 전 임시 비용입니다. 결과를 확정된 비용 기준으로 읽지
                마세요.
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  );
}
