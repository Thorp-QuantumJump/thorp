import { useId, useRef, useState, type ReactNode } from "react";
import { checkNumberInRange } from "@/hooks/useStrategySettings";
import type { RangeView } from "@/types/strategyRegistry";
import styles from "./AxisField.module.css";

interface AxisNumberFieldProps {
  label: string;
  unit?: string;
  /** 허용 범위. 등록표가 준 값을 그대로 넘긴다 */
  range: RangeView;
  defaultValue: number;
  /** 지금 상태에 들어 있는 값. 재검증이 비워 둔 항목이면 `undefined`다 */
  value: number | undefined;
  /** 범위 안이고 간격에 맞는 값일 때만 불린다 */
  onChange: (value: number) => void;
  onReset: () => void;
  /** 설명 옆에 붙일 용어 도움말. 없으면 그리지 않는다 */
  help?: ReactNode;
}

/** 입력칸의 글을 수로 읽는다. 빈칸·숫자가 아닌 글은 `null`이다. 쉼표는 자릿수 구분으로 보고 뺀다. */
function parseDraft(draft: string): number | null {
  const text = draft.replaceAll(",", "").trim();
  if (text === "") return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * 입력형 설정 항목(FR-006, quickstart 5).
 *
 * <p><b>허용 범위 밖이거나 간격에 맞지 않는 값은 상태에 반영하지 않는다.</b> 입력칸에는 사용자가 넣은
 * 글을 그대로 남기고 허용 범위를 안내한다 — 최솟값·최댓값으로 잘라 넣으면 사용자가 넣지 않은 값으로
 * 분석이 나간다. 판정은 보관값 재검증과 같은 `checkNumberInRange`를 써서, 입력 경로와 복원 경로가
 * 서로 다른 잣대를 쓰지 않게 한다(SC-002).
 *
 * <p><b>축을 모른다.</b> 라벨·단위·범위·기본값만 받으므로 분석 조건의 시작 금액(US3, T066)에도 그대로
 * 쓴다.
 *
 * <p>입력칸은 `type="number"`가 아니라 글자 칸이다. 숫자 칸은 브라우저가 화살표·휠로 값을 범위에 맞춰
 * 바꾸거나, 읽을 수 없는 글을 빈 값으로 바꿔 보내 "무엇을 넣었는지"를 화면이 알 수 없게 된다.
 */
export function AxisNumberField({
  label,
  unit,
  range,
  defaultValue,
  value,
  onChange,
  onReset,
  help,
}: AxisNumberFieldProps) {
  const inputId = useId();
  const messageId = useId();

  const [draft, setDraft] = useState(value === undefined ? "" : String(value));
  const [syncedValue, setSyncedValue] = useState(value);

  /**
   * 입력을 시작할 때 상태에 있던 값.
   *
   * <p>글자를 칠 때마다 판정하므로 `55`를 치는 도중의 `5`처럼 <b>범위 안인 중간 글자가 먼저
   * 반영된다.</b> 마지막 글이 범위 밖이면 그 중간 값이 상태에 남아, 입력칸은 "저장하지 않았습니다"라고
   * 말하는데 분석은 사용자가 의도한 적 없는 값으로 나간다. 그래서 글이 무효가 되면 입력을 시작하기
   * 전 값으로 되돌린다 — "저장하지 않았다"가 "이전 값 그대로다"와 같은 뜻이 된다.
   */
  const valueAtEditStart = useRef(value);

  // 바깥에서 값이 바뀌면(기본값 복원, 보관값 재검증) 입력칸을 그 값으로 맞춘다. 렌더 중에 맞춰
  // 효과를 거치지 않는다 — 한 번 옛 글이 그려졌다가 바뀌는 깜빡임이 없다.
  if (value !== syncedValue) {
    setSyncedValue(value);
    setDraft(value === undefined ? "" : String(value));
  }

  const unitText = unit ?? "";
  const rangeText = `허용 범위는 ${range.min}~${range.max}${unitText}, ${range.step}${unitText} 간격입니다.`;

  const parsed = parseDraft(draft);
  const problem = ((): string | null => {
    // 상태에 든 값과 같은 글이면 안내할 것이 없다.
    if (value !== undefined && parsed === value) return null;
    if (parsed === null) {
      return draft.trim() === ""
        ? `값을 입력해 주세요. ${rangeText}`
        : `숫자로 입력해 주세요. ${rangeText} 이 값은 저장하지 않았습니다.`;
    }
    const reason = checkNumberInRange(parsed, range);
    if (reason === "OUT_OF_RANGE") {
      return `${parsed}${unitText}은(는) 허용 범위 밖입니다. ${rangeText} 이 값은 저장하지 않았습니다.`;
    }
    if (reason === "OFF_STEP") {
      return `${parsed}${unitText}은(는) 등록된 간격에 맞지 않습니다. ${rangeText} 이 값은 저장하지 않았습니다.`;
    }
    return null;
  })();

  const handleChange = (next: string) => {
    setDraft(next);
    const nextValue = parseDraft(next);
    if (nextValue === null || checkNumberInRange(nextValue, range) !== null) {
      const before = valueAtEditStart.current;
      // 비워 둔 항목에서 시작했으면 되돌릴 값이 없다. 비우는 통로는 이 컴포넌트가 갖지 않는다.
      if (before !== undefined && before !== value) {
        // 되돌린 값이 바깥에서 돌아와도 입력칸의 글은 사용자가 친 그대로 둔다.
        setSyncedValue(before);
        onChange(before);
      }
      return;
    }
    // 이미 같은 값이면 부르지 않는다. 부르지 않아도 상태는 이미 그 값이다.
    if (nextValue === value) return;
    onChange(nextValue);
  };

  const handleReset = () => {
    // 상태가 이미 기본값이면 바깥 값이 바뀌지 않아 입력칸이 맞춰지지 않으므로 여기서 맞춘다.
    setDraft(String(defaultValue));
    onReset();
  };

  const describedBy = messageId;

  return (
    <div className={styles.field}>
      <div className={styles.header}>
        <label className={styles.label} htmlFor={inputId}>
          {label}
          {unit ? <span className={styles.unit}>({unit})</span> : null}
        </label>
        {/* 제목 바로 옆이다. header 가 양끝 정렬이라 label 밖에 두면 이 항목만 오른쪽 끝으로 밀린다. */}
        {help}
      </div>

      <div className={styles.inputRow}>
        <input
          id={inputId}
          className={styles.input}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={draft}
          aria-invalid={problem ? "true" : "false"}
          aria-describedby={describedBy}
          onFocus={() => {
            valueAtEditStart.current = value;
          }}
          onChange={(event) => handleChange(event.target.value)}
        />
        <button
          type="button"
          className={styles.reset}
          aria-label={`${label} 기본값 복원`}
          onClick={handleReset}
        >
          기본값 복원
        </button>
      </div>

      {/* 자리를 늘 두고 글만 바꾼다. 안내가 생길 때 자리가 새로 붙으면 보조기술이 읽지 않을 수 있다. */}
      <p
        id={messageId}
        className={problem ? styles.problem : styles.hint}
        aria-live="polite"
      >
        {problem ?? rangeText}
      </p>
    </div>
  );
}
