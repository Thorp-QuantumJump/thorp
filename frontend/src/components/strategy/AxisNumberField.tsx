import { useId, useRef, useState } from "react";
import { checkNumberInRange } from "@/hooks/useStrategySettings";
import type { RangeView } from "@/types/strategyRegistry";
import { type QuickAdd, groupThousands } from "./amountQuickAdd";
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
  /** 세 자리마다 쉼표를 넣어 보인다. 자릿수가 큰 금액에서만 켠다 */
  groupDigits?: boolean;
  /**
   * 간격에 맞지 않는 값을 **가장 가까운 간격으로 반올림해 받는다**(팀 결정).
   *
   * 기본값은 꺼짐이다. 사용자가 넣지 않은 값으로 분석이 나가지 않게 하는 것이 원래 규칙이라,
   * 켜는 자리를 부르는 쪽이 하나씩 정한다.
   */
  roundToStep?: boolean;
  /** 누르면 그만큼 더하는 단추들. 주지 않으면 그리지 않는다 */
  quickAdd?: readonly QuickAdd[];
  /**
   * 문제가 없을 때도 허용 범위 문장을 칸 아래에 늘 보인다. 기본값은 켜짐이다. 끄면 그 문장은 입력칸에 초점이
   * 있는 동안만 칸 위 안내 말풍선으로 뜨고, 범위 밖·간격 밖 값을 넣었을 때는 칸 아래 안내가 범위를 함께
   * 말한다(US2 시나리오 3). 빠른 더하기가 있어 범위를 늘 읽을 일이 적은 시작 금액이 끈다(2026-10-01)
   */
  rangeHint?: boolean;
}

/** 간격의 소수 자릿수. 반올림한 값에 부동소수 찌꺼기가 남지 않게 다시 자른다. */
function decimalsOf(step: number): number {
  const text = String(step);
  const dot = text.indexOf(".");
  return dot === -1 ? 0 : text.length - dot - 1;
}

/** 가장 가까운 간격으로 맞춘다. 범위 밖으로는 나가지 않는다. */
function snapToStep(value: number, range: RangeView): number {
  const steps = Math.round((value - range.min) / range.step);
  const snapped = Number(
    (range.min + steps * range.step).toFixed(decimalsOf(range.step)),
  );
  if (snapped < range.min) return range.min;
  if (snapped > range.max) return range.max;
  return snapped;
}

/** 입력칸의 글을 수로 읽는다. 빈칸·숫자가 아닌 글은 `null`이다. 쉼표는 자릿수 구분으로 보고 뺀다. */
function parseDraft(draft: string): number | null {
  const text = draft.replaceAll(",", "").trim();
  if (text === "") return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * 입력형 설정 항목(FR-006, quickstart 5). 설정 행(SettingRow)의 값을 눌러 연 자리에 놓인다 — 이름·도움말·
 * 되돌리기는 행 머리가 맡고, 입력칸은 `aria-label`로 행과 같은 이름을 가진다. 되돌리면 행이 이 필드를 새로
 * 그려 입력 중이던 글까지 기본값으로 맞춘다.
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
  groupDigits = false,
  roundToStep = false,
  quickAdd,
  rangeHint = true,
}: AxisNumberFieldProps) {
  const messageId = useId();
  const tooltipId = useId();
  const [focused, setFocused] = useState(false);

  /** 칸에 적을 모양. 쉼표는 보기 위한 것이고 `parseDraft`가 읽을 때 걷어 낸다. */
  const show = (next: number) =>
    groupDigits ? groupThousands(String(next)) : String(next);

  const [draft, setDraft] = useState(value === undefined ? "" : show(value));
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
    setDraft(value === undefined ? "" : show(value));
  }

  const unitText = unit ?? "";
  // 금액처럼 자릿수가 큰 칸은 범위도 칸과 같은 모양(쉼표)으로 적는다. `1000000000`은 몇 자리인지 읽히지 않는다.
  const rangeText = `허용 범위는 ${show(range.min)}~${show(range.max)}${unitText}, ${show(range.step)}${unitText} 간격입니다.`;

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

  /** 값을 확정하고 칸의 글까지 그 값의 모양으로 맞춘다. */
  const commit = (next: number) => {
    // 바깥 값이 돌아오며 `setDraft`를 덮어쓰지 않게 먼저 맞춰 둔다.
    setSyncedValue(next);
    setDraft(show(next));
    if (next !== value) onChange(next);
  };

  /*
    칸을 떠날 때 정리한다. 글자를 칠 때마다 반올림하면 `1000000`을 치는 도중의 `1`이 최솟값으로
    튀어 무엇을 치고 있었는지 알 수 없게 된다.
  */
  const handleBlur = () => {
    const parsedNow = parseDraft(draft);
    if (parsedNow === null) return;
    const reason = checkNumberInRange(parsedNow, range);
    if (reason === null) {
      commit(parsedNow);
      return;
    }
    if (roundToStep && reason === "OFF_STEP")
      commit(snapToStep(parsedNow, range));
  };

  /** 지금 값에서 그만큼 더한다. 비어 있으면 기본값에서 시작한다. */
  const handleQuickAdd = (amount: number) => {
    const base = value ?? defaultValue;
    const next = base + amount;
    if (next > range.max) return;
    commit(snapToStep(next, range));
  };

  // 설명 문단은 도움말로 내려 사라졌다. 남은 것은 범위·문제 안내 한 줄이다. 말풍선은 문제가 없을 때만 뜬다 —
  // 문제 안내가 이미 범위를 말하므로 같은 문장을 두 번 띄우지 않는다.
  const showTooltip = !rangeHint && focused && problem === null;
  const describedBy = showTooltip ? `${messageId} ${tooltipId}` : messageId;

  return (
    <div className={styles.field}>
      {/* 빠른 더하기와 입력칸이 한 줄이다. 입력칸이 오른쪽 끝에 서서 행의 값과 같은 선에 놓인다. */}
      <div className={styles.inputRow}>
        {/* 자주 쓰는 액수를 한 번에 더한다. 상한을 넘게 되는 단추는 누를 수 없다. */}
        {quickAdd && quickAdd.length > 0 ? (
          <div className={styles.quickAdd}>
            {quickAdd.map((item) => (
              <button
                key={item.label}
                type="button"
                className={styles.quickAddButton}
                aria-label={`${label} ${item.label}`}
                disabled={(value ?? defaultValue) + item.amount > range.max}
                onClick={() => handleQuickAdd(item.amount)}
              >
                {item.label}
              </button>
            ))}
          </div>
        ) : null}
        <span className={styles.inputAnchor}>
          <input
            className={styles.input}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={draft}
            aria-label={label}
            aria-invalid={problem ? "true" : "false"}
            aria-describedby={describedBy}
            onFocus={() => {
              valueAtEditStart.current = value;
              setFocused(true);
            }}
            onChange={(event) => handleChange(event.target.value)}
            onBlur={() => {
              setFocused(false);
              handleBlur();
            }}
          />
          {showTooltip ? (
            <span id={tooltipId} role="tooltip" className={styles.rangeTooltip}>
              {rangeText}
            </span>
          ) : null}
        </span>
      </div>

      {/* 자리를 늘 두고 글만 바꾼다. 안내가 생길 때 자리가 새로 붙으면 보조기술이 읽지 않을 수 있다. */}
      <p
        id={messageId}
        className={problem ? styles.problem : styles.hint}
        aria-live="polite"
      >
        {problem ?? (rangeHint ? rangeText : null)}
      </p>
    </div>
  );
}
