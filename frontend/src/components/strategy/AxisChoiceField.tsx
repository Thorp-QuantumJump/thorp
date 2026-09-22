import { useId, type ReactNode } from "react";
import { axisValueEquals } from "@/hooks/useStrategySettings";
import type { AxisValue, AxisView } from "@/types/strategyRegistry";
import styles from "./AxisField.module.css";

interface AxisChoiceFieldProps {
  /** `inputType`이 `CHOICE`인 축. 후보·문구·단위를 모두 여기서 읽는다 */
  axis: AxisView;
  /** 지금 값. 재검증이 비워 둔 축이면 `undefined`이고 아무 후보도 고른 상태가 아니다 */
  value: AxisValue | undefined;
  /** 등록된 후보 가운데 고를 수 있는 것을 골랐을 때만 불린다. 값은 후보의 것을 그대로 넘긴다 */
  onChange: (value: AxisValue) => void;
  onReset: () => void;
  /** 설명 옆에 붙일 용어 도움말. 없으면 그리지 않는다 */
  help?: ReactNode;
}

/**
 * 목록형 설정 항목(FR-006, 계약 §1 `choices`).
 *
 * <p><b>등록된 후보만 그린다. 다른 값을 넣을 경로가 없다.</b> 자유 입력칸을 두지 않으므로 등록되지
 * 않은 값은 이 컴포넌트를 거쳐 상태에 들어갈 수 없다.
 *
 * <p><b>고를 수 없는 후보도 목록에 남긴다.</b> `selectable: false`는 서버가 지금 섹터 수로 판정해
 * 내려 준 결과이며, 빼 버리면 사용자는 그 값이 왜 없는지 알 수 없다. 흐리게 보이고 선택은 막는다.
 *
 * <p><b>값의 뜻을 읽지 않는다.</b> 합성 값(리밸런싱 주기)도 등록표의 `label`로 보이고, 고르면 후보의
 * 값을 그대로 넘긴다. 칸 이름으로 문구를 짓지 않는다(FR-005).
 */
export function AxisChoiceField({
  axis,
  value,
  onChange,
  onReset,
  help,
}: AxisChoiceFieldProps) {
  const groupName = useId();
  const choices = axis.choices ?? [];

  return (
    <fieldset className={styles.field}>
      <legend className={styles.header}>
        <span className={styles.label}>
          {axis.label}
          {axis.unit ? (
            <span className={styles.unit}>({axis.unit})</span>
          ) : null}
          {help}
        </span>
      </legend>

      <div className={styles.choices}>
        {choices.map((choice, index) => {
          const checked =
            value !== undefined && axisValueEquals(choice.value, value);
          return (
            <label
              key={`${index}:${choice.label}`}
              className={styles.choice}
              data-selectable={choice.selectable ? "true" : "false"}
            >
              <input
                type="radio"
                name={groupName}
                checked={checked}
                disabled={!choice.selectable}
                onChange={() => {
                  if (choice.selectable) onChange(choice.value);
                }}
              />
              {choice.label}
              {choice.selectable ? null : (
                <span className={styles.unit}>
                  (지금 조건에서 고를 수 없음)
                </span>
              )}
            </label>
          );
        })}
      </div>

      <div>
        <button
          type="button"
          className={styles.reset}
          aria-label={`${axis.label} 기본값 복원`}
          onClick={onReset}
        >
          기본값 복원
        </button>
      </div>
    </fieldset>
  );
}
