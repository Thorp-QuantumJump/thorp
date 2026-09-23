import { useId, useState } from "react";
import { ChevronDown } from "lucide-react";
import { InlineHelp } from "@/components/common/InlineHelp";
import { TermHelp } from "@/components/learning/TermHelp";
import { applicableAxes, type AxisValues } from "@/hooks/useStrategySettings";
import type { LearningReturnState } from "@/types/learning";
import type { AxisValue, StrategyView } from "@/types/strategyRegistry";
import { checkRules } from "@/utils/validationRules";
import { AxisChoiceField } from "./AxisChoiceField";
import { AxisNumberField } from "./AxisNumberField";
import styles from "./DetailSettingsPanel.module.css";
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
   * 지금 설정으로 평가할 수 있는 범위. 워밍업을 이루는 축 아래에 그대로 적는다.
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
 * 전략 설정(US2, FR-003·FR-004·FR-006·FR-008·FR-012). 2026-09-22 까지 이름이 '상세 설정'이었다 — 옆의
 * '분석 조건'과 형제로 읽히도록 무엇의 설정인지를 이름에 담았다(`S15P21E205-406`).
 *
 * <p><b>접힌 채로 시작한다.</b> 기본 화면의 입력은 전략 선택 하나이고(SC-005), 펼쳤을 때만 항목을
 * 그린다. <b>선택한 전략의 축만 그리고 다른 전략의 축은 만들지도 않는다.</b>
 *
 * <p><b>전략을 모른다.</b> 항목은 `inputType`으로, 비중 안내는 등록표가 내려 준 `targetWeightSource`로
 * 가른다. 전략 ID나 축 이름으로 분기하지 않는다(FR-005, research R7-1).
 *
 * <p><b>규칙 위반은 펼치지 않아도 보인다.</b> 값을 바꾼 뒤 패널을 접어도 위반 안내가 함께 접히면
 * 사용자는 왜 분석할 수 없는지 모른다. 판정은 값이 바뀔 때마다 화면 안에서 하고 서버에 묻지 않는다.
 *
 * <p><b>조건 밖 축은 그리지 않는다.</b> 선정 방식이 가리키지 않는 값은 보이지도 저장되지도 않는다
 * (FR-003). 무엇이 조건 밖인지는 등록표의 `appliesWhen`이 정하고 화면은 `applicableAxes`로 기계적으로
 * 가른다.
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
  const [open, setOpen] = useState(false);
  const bodyId = useId();

  // 등록표가 규칙 문법을 어겼으면 통과로 접지 않고 판정하지 못했다고 알린다. 페이지가 '분석하기'를
  // 막을 때도 같은 함수를 쓴다.
  // 등록표가 정한 워밍업 구성 축. 숫자 항목(상수)은 축이 아니라 제외한다.
  const warmupAxes = new Set(
    (strategy.warmupRule?.sum ?? []).filter(
      (term): term is string => typeof term === "string",
    ),
  );

  const { violations, unjudged: rulesUnjudged } = checkRules(
    strategy.validationRules,
    values,
    {
      UNIVERSE_SIZE: universeSize,
    },
  );

  return (
    <section className={styles.panel} aria-label="전략 설정">
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((current) => !current)}
      >
        <span>전략 설정</span>
        <ChevronDown
          className={styles.toggleIcon}
          size={16}
          strokeWidth={1.8}
          aria-hidden="true"
        />
      </button>

      {open ? (
        <div id={bodyId} className={styles.body}>
          <p className={styles.note}>
            처음 보이는 값은 등록표의 기본값입니다. 편의용 출발점이며 최적값이나
            투자 권고가 아닙니다.
          </p>

          {strategy.targetWeightSource === "USER" ? (
            <TargetWeightEditor
              rows={targetWeights}
              onChange={onTargetWeightChange}
              onDistributeEven={onDistributeWeightsEven}
            />
          ) : null}
          {strategy.targetWeightSource === "COMPUTED" ? (
            <p className={styles.note}>
              출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.
            </p>
          ) : null}

          {strategy.axes.length === 0 ? (
            <p className={styles.note}>
              이 전략에는 바꿀 수 있는 설정 항목이 없습니다.
            </p>
          ) : (
            <div className={styles.axes}>
              {applicableAxes(strategy, values).map((axis) => {
                const value = values[axis.name];
                // 이 축이 워밍업을 이루는가. 등록표 `warmupRule.sum`이 정하므로 축 이름을 화면에
                // 적어 두지 않는다(FR-005). 이런 축은 값이 평가 범위를 움직이므로 그 사실을 함께 적는다.
                const movesRange = warmupAxes.has(axis.name);
                const rangeNote =
                  movesRange && evaluationRange !== null
                    ? `지금 값으로 평가할 수 있는 범위는 ${evaluationRange.start} ~ ${evaluationRange.end}입니다.`
                    : undefined;
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
                if (axis.inputType === "CHOICE") {
                  return (
                    <AxisChoiceField
                      key={axis.name}
                      axis={axis}
                      value={value}
                      onChange={(next) => onChange(axis.name, next)}
                      onReset={() => onResetAxis(axis.name)}
                      help={help}
                      note={rangeNote}
                    />
                  );
                }
                // 계약은 입력형 축에 범위와 숫자 기본값을 싣는다. 어겼으면 그리지 못한 사실을 말한다.
                const { range, defaultValue } = axis;
                if (!range || typeof defaultValue !== "number") {
                  return (
                    <p key={axis.name} className={styles.note} role="status">
                      {axis.label}: {AXIS_UNRENDERABLE}
                    </p>
                  );
                }
                return (
                  <AxisNumberField
                    key={axis.name}
                    label={axis.label}
                    unit={axis.unit}
                    range={range}
                    defaultValue={defaultValue}
                    value={typeof value === "number" ? value : undefined}
                    onChange={(next) => onChange(axis.name, next)}
                    onReset={() => onResetAxis(axis.name)}
                    help={help}
                    note={rangeNote}
                  />
                );
              })}
            </div>
          )}

          {strategy.axes.length > 0 ? (
            <div>
              <button
                type="button"
                className={styles.resetAll}
                onClick={onResetAll}
              >
                모두 기본값 복원
              </button>
            </div>
          ) : null}
        </div>
      ) : null}

      <RuleViolationNotice violations={violations} />
      {rulesUnjudged ? (
        <p className={styles.violations} role="alert">
          {RULES_UNJUDGED}
        </p>
      ) : null}
    </section>
  );
}
