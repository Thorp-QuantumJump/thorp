import { axisValueEquals } from "@/hooks/useStrategySettings";
import type { AxisValue, AxisView } from "@/types/strategyRegistry";
import { InlineChoices } from "./SettingRow";

interface AxisChoiceFieldProps {
  /** `inputType`이 `CHOICE`인 축. 후보·문구를 모두 여기서 읽는다 */
  axis: AxisView;
  /** 지금 값. 재검증이 비워 둔 축이면 `undefined`이고 아무 후보도 고른 상태가 아니다 */
  value: AxisValue | undefined;
  /** 등록된 후보 가운데 고를 수 있는 것을 골랐을 때만 불린다. 값은 후보의 것을 그대로 넘긴다 */
  onChange: (value: AxisValue) => void;
}

/**
 * 목록형 설정 항목(FR-006, 계약 §1 `choices`). 설정 행(SettingRow) 안에 놓인다 — 이름·단위·도움말·되돌리기는
 * 행 머리가 맡고, 이 컴포넌트는 후보만 그린다. 후보가 몇 개뿐이면 행 오른쪽에, 많으면 펼친 자리에 선다.
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
export function AxisChoiceField({ axis, value, onChange }: AxisChoiceFieldProps) {
  return (
    <InlineChoices
      label={axis.label}
      choices={(axis.choices ?? []).map((choice, index) => ({
        key: `${index}:${choice.label}`,
        label: choice.label,
        checked: value !== undefined && axisValueEquals(choice.value, value),
        disabled: !choice.selectable,
        onSelect: () => onChange(choice.value),
      }))}
    />
  );
}
