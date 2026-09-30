import { RotateCcw } from "lucide-react";
import type { ReactNode } from "react";
import { InlineHelp } from "@/components/common/InlineHelp";
import { TermHelp } from "@/components/learning/TermHelp";
import {
  applicableAxes,
  axisValueEquals,
  type AxisValues,
} from "@/hooks/useStrategySettings";
import type { LearningReturnState } from "@/types/learning";
import type { AxisValue, AxisView, StrategyView } from "@/types/strategyRegistry";
import { checkRules } from "@/utils/validationRules";
import { AxisChoiceField } from "./AxisChoiceField";
import { AxisNumberField } from "./AxisNumberField";
import styles from "./DetailSettingsPanel.module.css";
import { SettingGroup, SettingRow } from "./SettingRow";
import {
  TargetWeightEditor,
  type TargetWeightRow,
} from "./TargetWeightEditor";
import { RuleViolationNotice } from "./RuleViolationNotice";

interface DetailSettingsPanelProps {
  /** 선택한 전략. 이 전략의 축만 그린다 */
  strategy: StrategyView;
  /** 선택 전략의 지금 값. 재검증이 비워 둔 축은 키가 없다 */
  values: AxisValues;
  /** 후보 섹터 집합의 크기. 규칙의 `UNIVERSE_SIZE` 맥락이다 */
  universeSize: number;
  /** 앞 단계에서 고른 대상과 지금 비중. `targetWeightSource`가 `USER`일 때만 쓴다 */
  targetWeights: readonly TargetWeightRow[] | null;
  /** 한 대상의 비중을 고친다. 받아들인 값만 올라온다 */
  onTargetWeightChange: (sectorId: string, weightTenths: number) => void;
  /** 고른 대상 전체를 같은 비중으로 다시 채운다 */
  onDistributeWeightsEven: () => void;
  /**
   * 지금 설정으로 평가할 수 있는 범위. 워밍업을 이루는 축 묶음 아래에 한 번 적는다.
   *
   * <p>그 축을 늘리면 앞이 깎여 범위가 좁아진다 — 값을 고르는 자리에서 그 사실이 보여야 무엇을 잃고
   * 무엇을 얻는지 견줄 수 있다. 판정할 수 없으면 `null`이고 아무것도 적지 않는다(원칙 VI).
   */
  evaluationRange: { start: string; end: string } | null;
  onChange: (axisName: string, value: AxisValue) => void;
  onResetAxis: (axisName: string) => void;
  onResetAll: () => void;
}

const RULES_UNJUDGED = "설정 규칙을 판정하지 못했습니다.";

/** 도움말에서 글로 갔다가 돌아올 자리. 이 화면의 경로와 이름이다(013 term-help-ui §3). */
const RETURN_TO: LearningReturnState = {
  path: "/strategy",
  label: "전략 선택",
};
const AXIS_UNRENDERABLE =
  "이 항목의 허용 범위를 확인하지 못해 값을 바꿀 수 없습니다.";

/**
 * 행 오른쪽에 바로 늘어놓을 후보의 최대 개수. 이보다 많으면 한 줄이 넘쳐 값을 눌러 펼친다. 전략 지식이 아니라
 * 한 행에 무리 없이 서는 후보 수다.
 */
const INLINE_CHOICE_LIMIT = 4;

/** 재검증이 비워 둔 축. 값이 없다는 사실을 그대로 적는다(원칙 VI). */
const EMPTY = "비어 있음";

/** 행에 보일 지금 값. 목록형은 등록표의 후보 라벨로, 입력형은 숫자와 단위로 적는다(FR-005). */
function describeAxisValue(axis: AxisView, value: AxisValue | undefined): string {
  if (value === undefined) return EMPTY;
  if (axis.inputType === "CHOICE") {
    const choice = (axis.choices ?? []).find((candidate) =>
      axisValueEquals(candidate.value, value),
    );
    return choice?.label ?? EMPTY;
  }
  return typeof value === "number"
    ? `${value.toLocaleString("ko-KR")}${axis.unit ?? ""}`
    : EMPTY;
}

/** 값이 등록표 기본값과 다른가. 재검증이 비워 둔 축도 되돌릴 거리가 있다. */
function isAxisModified(axis: AxisView, value: AxisValue | undefined): boolean {
  if (value === undefined) return true;
  return axis.defaultValue !== undefined && !axisValueEquals(value, axis.defaultValue);
}

/**
 * 행 이름 옆에 단위를 적을까. 값이 이미 단위를 담고 있으면 한 행에서 같은 말을 두 번 하게 된다 — 입력형은 값에
 * 단위를 붙여 보이고, 목록형은 후보 라벨이 모두 그 단위로 끝나면 적지 않는다. 등록표 글자만 견주는 기계적
 * 판정이며 축 이름으로 가르지 않는다(FR-005).
 */
function rowUnit(axis: AxisView): string | undefined {
  if (!axis.unit || axis.inputType !== "CHOICE") return undefined;
  const unit = axis.unit;
  const labels = (axis.choices ?? []).map((choice) => choice.label);
  return labels.length > 0 && labels.every((label) => label.endsWith(unit))
    ? undefined
    : unit;
}

/**
 * 전략 설정(US2, FR-003·FR-006·FR-008).
 *
 * <p><b>축마다 한 행이다.</b> 2026-09-30까지는 '전략 설정' 아코디언 하나에 축을 모두 넣었는데, 동일
 * 비중처럼 축이 하나인 전략에서는 묶음 이름이 항목 이름을 가렸고, 축이 여덟인 전략에서는 하나를 고치려
 * 전부 펼쳐야 했다. 지금은 행이 이름과 지금 값을 늘 보이고, 후보가 몇 개뿐인 목록형 축은 행 오른쪽에서
 * 바로 고르며, 숫자와 긴 목록만 값을 눌러 편다(SC-005, 2026-09-30 개정). 되돌리기는 바꾼 행 이름 옆에만
 * 아이콘으로 나타나고, '모두 기본값 복원'은 축이 둘 이상이고 바꾼 값이 있을 때만 묶음 제목 옆에 선다.
 *
 * <p><b>기본값이 편의용 출발점이라는 말(FR-004)과 자동 배분의 출력 비중 설명(FR-012)은 이 묶음이 아니라
 * 상세 설정 카드 머리에 둔다.</b> 분석 조건의 기본값에도 해당하는 말이고, 한쪽 열에만 두면 두 열의 행 선이
 * 어긋난다.
 *
 * <p><b>선택한 전략의 축만 그리고 다른 전략의 축은 만들지도 않는다.</b>
 *
 * <p><b>전략을 모른다.</b> 항목은 `inputType`으로, 비중 편집기는 등록표가 내려 준 `targetWeightSource`로
 * 가른다. 전략 ID나 축 이름으로 분기하지 않는다(FR-005, research R7-1).
 *
 * <p><b>규칙 위반은 펼치지 않아도 보인다.</b> 값을 바꾼 뒤 행을 접어도 위반 안내가 함께 접히면
 * 사용자는 왜 분석할 수 없는지 모른다. 판정은 값이 바뀔 때마다 화면 안에서 하고 서버에 묻지 않는다.
 *
 * <p><b>조건 밖 축은 그리지 않는다.</b> 선정 방식이 가리키지 않는 값은 보이지도 저장되지도 않는다
 * (FR-003). 무엇이 조건 밖인지는 등록표의 `appliesWhen`이 정하고 화면은 `applicableAxes`로 기계적으로
 * 가른다.
 *
 * <p><b>평가 가능 범위는 워밍업 축 묶음마다 한 번 적는다.</b> 워밍업을 이루는 축(등록표 `warmupRule.sum`)이
 * 이어서 놓이면 그 묶음의 마지막 행 아래에만 적는다 — 같은 날짜를 축마다 되풀이하면 경고가 많다는 인상만 남는다.
 *
 * <p><b>용어 도움말은 등록표가 `termId`를 준 축에만 붙인다</b>(FR-016). 어느 축이 어느 용어인지를 화면이
 * 축 이름으로 고르면 전략 지식의 중복이다.
 */
export function DetailSettingsPanel({
  strategy,
  values,
  universeSize,
  targetWeights,
  onTargetWeightChange,
  onDistributeWeightsEven,
  evaluationRange,
  onChange,
  onResetAxis,
  onResetAll,
}: DetailSettingsPanelProps) {
  // 등록표가 정한 워밍업 구성 축. 숫자 항목(상수)은 축이 아니라 제외한다.
  const warmupAxes = new Set(
    (strategy.warmupRule?.sum ?? []).filter(
      (term): term is string => typeof term === "string",
    ),
  );

  // 등록표가 규칙 문법을 어겼으면 통과로 접지 않고 판정하지 못했다고 알린다. 페이지가 '분석하기'를
  // 막을 때도 같은 함수를 쓴다.
  const { violations, unjudged: rulesUnjudged } = checkRules(
    strategy.validationRules,
    values,
    {
      UNIVERSE_SIZE: universeSize,
    },
  );

  const axes = applicableAxes(strategy, values);
  const anyModified = axes.some((axis) => isAxisModified(axis, values[axis.name]));

  const weightTotal =
    targetWeights === null
      ? null
      : targetWeights.reduce((sum, row) => sum + row.weightTenths, 0) / 10;

  const rangeLine =
    evaluationRange !== null
      ? `평가 가능 범위 ${evaluationRange.start} ~ ${evaluationRange.end}`
      : null;

  return (
    <SettingGroup
      title="전략 설정"
      action={
        strategy.axes.length > 1 && anyModified ? (
          <button
            type="button"
            className={styles.resetAll}
            onClick={onResetAll}
          >
            <RotateCcw size={12} strokeWidth={2.2} aria-hidden="true" />
            모두 기본값 복원
          </button>
        ) : undefined
      }
      footer={
        <>
          <RuleViolationNotice violations={violations} />
          {rulesUnjudged ? (
            <p className={styles.violations} role="alert">
              {RULES_UNJUDGED}
            </p>
          ) : null}
        </>
      }
    >
      {strategy.targetWeightSource === "USER" ? (
        <SettingRow
          label="목표 비중"
          value={
            targetWeights === null
              ? "확인할 수 없음"
              : `${targetWeights.length}개 대상 · 합계 ${weightTotal}%`
          }
        >
          <TargetWeightEditor
            rows={targetWeights}
            onChange={onTargetWeightChange}
            onDistributeEven={onDistributeWeightsEven}
          />
        </SettingRow>
      ) : null}

      {strategy.axes.length === 0 ? (
        <p className={styles.note}>
          이 전략에는 바꿀 수 있는 설정 항목이 없습니다.
        </p>
      ) : (
        axes.map((axis, index) => {
          const value = values[axis.name];
          // 이 축이 워밍업을 이루는가. 등록표 `warmupRule.sum`이 정하므로 축 이름을 화면에
          // 적어 두지 않는다(FR-005). 이런 축은 값이 평가 범위를 움직이므로 그 사실을 함께 적는다.
          const movesRange = warmupAxes.has(axis.name);
          // 이어지는 워밍업 축 묶음의 마지막 행에만 범위를 적는다.
          const next = axes[index + 1];
          const endsRangeGroup =
            movesRange && (next === undefined || !warmupAxes.has(next.name));
          const description =
            movesRange && axis.description
              ? `${axis.description} 이 값을 바꾸면 평가할 수 있는 범위가 달라집니다.`
              : axis.description;

          // 한 항목에 물음표는 하나다. 등록표의 축 설명은 용어 도움말이 있으면 그 안에 함께
          // 싣고, 없으면 화면이 가진 문장을 담는 InlineHelp 로 보인다(`S15P21E205-406`).
          const help = axis.termId ? (
            <TermHelp
              termId={axis.termId}
              label={axis.label}
              returnTo={RETURN_TO}
              note={description}
              on="hover"
            />
          ) : description ? (
            <InlineHelp label={axis.label} on="hover">
              {description}
            </InlineHelp>
          ) : undefined;

          const modified = isAxisModified(axis, value);
          const reset = () => onResetAxis(axis.name);
          const captionParts: ReactNode[] = [];

          if (axis.inputType === "CHOICE") {
            const choices = axis.choices ?? [];
            // 고를 수 없는 후보는 빼지 않고 점선으로 두며, 까닭은 행 아래 한 줄이 말한다(FR-006, NFR-002).
            const blocked = choices
              .filter((choice) => !choice.selectable)
              .map((choice) => choice.label);
            if (blocked.length > 0) {
              captionParts.push(
                <span key="blocked">{`${blocked.join(" · ")}: 지금 조건에서 고를 수 없습니다.`}</span>,
              );
            }
            if (endsRangeGroup && rangeLine) {
              captionParts.push(<span key="range">{rangeLine}</span>);
            }
            const caption =
              captionParts.length > 0 ? (
                <span className={styles.captionLines}>{captionParts}</span>
              ) : undefined;
            const field = (
              <AxisChoiceField
                axis={axis}
                value={value}
                onChange={(nextValue) => onChange(axis.name, nextValue)}
              />
            );
            // 후보가 몇 개뿐이면 펼칠 까닭이 없다. 행 오른쪽에 바로 둔다.
            if (choices.length <= INLINE_CHOICE_LIMIT) {
              return (
                <SettingRow
                  key={axis.name}
                  label={axis.label}
                  unit={rowUnit(axis)}
                  help={help}
                  caption={caption}
                  modified={modified}
                  onReset={reset}
                  control={field}
                />
              );
            }
            return (
              <SettingRow
                key={axis.name}
                label={axis.label}
                unit={rowUnit(axis)}
                help={help}
                caption={caption}
                modified={modified}
                onReset={reset}
                value={describeAxisValue(axis, value)}
              >
                {field}
              </SettingRow>
            );
          }
          // 계약은 입력형 축에 범위와 숫자 기본값을 싣는다. 어겼으면 고칠 자리를 열지 않고, 그 사실을 늘 보인다.
          const { range, defaultValue } = axis;
          if (!range || typeof defaultValue !== "number") {
            return (
              <SettingRow
                key={axis.name}
                label={axis.label}
                help={help}
                value={describeAxisValue(axis, value)}
                caption={<span role="status">{AXIS_UNRENDERABLE}</span>}
              />
            );
          }
          return (
            <SettingRow
              key={axis.name}
              label={axis.label}
              help={help}
              caption={endsRangeGroup && rangeLine ? rangeLine : undefined}
              modified={modified}
              onReset={reset}
              value={describeAxisValue(axis, value)}
            >
              <AxisNumberField
                label={axis.label}
                unit={axis.unit}
                range={range}
                defaultValue={defaultValue}
                value={typeof value === "number" ? value : undefined}
                onChange={(nextValue) => onChange(axis.name, nextValue)}
              />
            </SettingRow>
          );
        })
      )}
    </SettingGroup>
  );
}
