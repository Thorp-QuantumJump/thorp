import { ChevronDown, RotateCcw } from "lucide-react";
import {
  useEffect,
  useId,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from "react";
import styles from "./SettingRow.module.css";

interface SettingGroupProps {
  /** 묶음 제목('분석 조건', '전략 설정'). 영역 이름도 이 제목에서 온다 */
  title: string;
  /** 제목 오른쪽 끝의 행동('모두 기본값 복원'). 없으면 비운다 */
  action?: ReactNode;
  children: ReactNode;
  /** 행 목록 아래에 늘 보이는 것(규칙 위반 안내) */
  footer?: ReactNode;
}

/**
 * 설정 항목 행들을 묶는 자리다. 제목 한 줄과 행 목록으로 이루어진다.
 *
 * <p>묶음 자체는 접히지 않는다. 항목마다 지금 값을 한 줄로 보이고, 고칠 것이 많은 항목만 그 자리에서
 * 펼치므로 묶음을 통째로 여닫는 단추가 있으면 여는 자리가 두 겹이 된다. 영역 이름은 보이는 제목을 그대로
 * 가리켜(`aria-labelledby`) 보이는 이름과 읽히는 이름이 갈리지 않게 한다.
 */
export function SettingGroup({ title, action, children, footer }: SettingGroupProps) {
  const headingId = useId();
  return (
    <section className={styles.group} aria-labelledby={headingId}>
      <div className={styles.groupHead}>
        <h3 id={headingId} className={styles.groupTitle}>
          {title}
        </h3>
        {action}
      </div>
      <div className={styles.rows}>{children}</div>
      {footer}
    </section>
  );
}

export type InlineChoice = {
  key: string;
  label: string;
  checked: boolean;
  /** 지금 조건에서 고를 수 없는 후보. 빼지 않고 흐리게 두며 낱말로도 알린다 */
  disabled?: boolean;
  onSelect: () => void;
};

interface InlineChoicesProps {
  /** 묶음의 접근 가능 이름. 행 이름과 같게 둔다 */
  label: string;
  choices: readonly InlineChoice[];
}

/**
 * 행 오른쪽에 바로 두는 선택지. 후보들이 한 트랙 안에 나란히 서고 고른 것만 채워진다 — 후보마다 테두리를
 * 두르면 여러 행이 모인 화면이 버튼 벽으로 읽힌다. 라디오 묶음이라 키보드 화살표로 옮기고 보조기술에 선택이
 * 읽힌다. 후보를 등록표에서 받은 그대로 늘어놓으며 순서나 문구를 짓지 않는다(FR-005).
 *
 * <p>고를 수 없는 후보는 빼지 않는다(FR-006). 점선으로 흐리게 두고, 까닭은 보조기술에 후보 이름과 함께
 * 읽히며 눈으로는 행 아래 한 줄이 말한다(NFR-002).
 */
export function InlineChoices({ label, choices }: InlineChoicesProps) {
  const name = useId();
  return (
    <fieldset className={styles.choices} aria-label={label}>
      {choices.map((choice) => (
        <label
          key={choice.key}
          className={styles.choice}
          data-selectable={choice.disabled ? "false" : "true"}
        >
          <input
            type="radio"
            name={name}
            checked={choice.checked}
            disabled={choice.disabled}
            onChange={() => {
              if (!choice.disabled) choice.onSelect();
            }}
          />
          {choice.label}
          {choice.disabled ? (
            <span className={styles.srOnly}> (지금 조건에서 고를 수 없음)</span>
          ) : null}
        </label>
      ))}
    </fieldset>
  );
}

/** 선택지 옆에 붙이는 작은 선택 상자(목표 변동성처럼 켰을 때만 고르는 값). */
export function SettingSelect(props: ComponentPropsWithoutRef<"select">) {
  return <select {...props} className={styles.select} />;
}

interface SettingRowProps {
  label: string;
  /** 이름 옆 작은 단위 표기. 후보 라벨이 단위를 담지 않은 목록형 축만 쓴다 */
  unit?: string;
  /** 이름 옆 도움말(InlineHelp·TermHelp). 조작 요소 밖에 두어 도움말을 열어도 값이 바뀌지 않는다 */
  help?: ReactNode;
  /**
   * 행 오른쪽에 바로 두는 조작 요소. 후보가 몇 개뿐인 선택처럼 펼칠 까닭이 없는 항목이 쓴다. 주면 `value`와
   * `children`은 쓰지 않는다
   */
  control?: ReactNode;
  /** 지금 적용되는 값. 조작 요소가 없는 행에서 오른쪽에 늘 보인다 */
  value?: ReactNode;
  /**
   * 값을 눌러 펼치는 편집 폼. 날짜·금액·숫자처럼 범위 안내가 따라야 하는 항목만 쓴다. 없고 `control`도 없으면
   * 값만 보이는 읽기 전용 행이다
   */
  children?: ReactNode;
  /** 행 아래 한 줄. 이 값이 다른 것을 움직이거나 읽기 전용 수치를 덧붙일 때 쓴다 */
  caption?: ReactNode;
  /** 값이 기본값과 다른가. 참일 때만 이름 옆에 되돌리는 아이콘이 나타난다 */
  modified?: boolean;
  onReset?: () => void;
  /** 되돌리는 아이콘의 이름. 기본은 '{항목} 기본값 복원'이다 */
  resetLabel?: string;
}

/** 되돌린 뒤 초점을 받을 자리. 펼침 단추, 고른 선택지, 첫 선택지, 행 자신 차례로 찾는다. */
const FOCUS_AFTER_RESET = [
  '[data-slot="setting-toggle"]',
  'input[type="radio"]:checked:not(:disabled)',
  'input[type="radio"]:not(:disabled)',
];

/**
 * 설정 항목 한 행(FR-002·FR-003).
 *
 * <p><b>이름은 왼쪽, 값과 조작은 오른쪽 끝에 맞춘다.</b> 모든 행의 오른쪽 끝이 구분선 끝에 서야 값이 한 선에
 * 늘어서 훑어 읽을 수 있다. 테두리가 있는 것(선택지 트랙, 값 단추)은 바꿀 수 있고, 맨 글씨는 읽기 전용이다.
 *
 * <p><b>펼칠 까닭이 없으면 펼치지 않는다.</b> 후보가 몇 개뿐인 선택은 그 자리에 바로 두고(`control`), 날짜·금액·
 * 숫자처럼 허용 범위와 입력 문제를 함께 읽어야 하는 항목만 값을 눌러 그 자리에서 펼친다(`children`).
 *
 * <p><b>되돌리기는 바꾼 행에만, 이름 옆에 나타난다.</b> 모든 행에 '기본값 복원'을 늘어놓으면 무엇을 바꿨는지가
 * 오히려 묻힌다. 이름 옆에 두면 바꾼 행이라는 표시를 겸하고, 값의 오른쪽 끝도 밀리지 않는다. 누르면 아이콘이
 * 사라지므로 초점을 그 행의 조작으로 옮긴다 — 옮기지 않으면 초점이 문서 맨 앞으로 떨어진다(NFR-001). 펼친 폼은
 * 새로 그려 입력 중이던 글까지 기본값으로 맞춘다.
 *
 * <p><b>펼침 단추의 이름은 '항목 이름 + 지금 값'이다.</b> 보이는 글자가 값이므로 이름에 값이 들어가야 음성으로
 * 부르는 사용자가 찾고, 화면 읽기 사용자가 펼치지 않아도 값을 듣는다(WCAG 2.5.3).
 */
export function SettingRow({
  label,
  unit,
  help,
  control,
  value,
  children,
  caption,
  modified = false,
  onReset,
  resetLabel,
}: SettingRowProps) {
  const [open, setOpen] = useState(false);
  // 되돌릴 때마다 올려 펼친 폼을 새로 그린다. 입력칸이 바깥 값과 같은 값을 받으면 스스로 맞추지 못한다.
  const [bodyKey, setBodyKey] = useState(0);
  const [focusAfterReset, setFocusAfterReset] = useState(false);
  const rowRef = useRef<HTMLDivElement>(null);
  const bodyId = useId();
  const labelId = useId();
  const valueId = useId();
  const editable = control === undefined && children !== undefined && children !== null;

  // 되돌린 값이 그려진 뒤에 옮긴다. 선택지는 되돌린 값이 그려져야 고른 후보가 바뀐다.
  useEffect(() => {
    if (!focusAfterReset) return;
    setFocusAfterReset(false);
    const row = rowRef.current;
    if (!row) return;
    for (const selector of FOCUS_AFTER_RESET) {
      const target = row.querySelector<HTMLElement>(selector);
      if (target) {
        target.focus();
        return;
      }
    }
    row.focus();
  }, [focusAfterReset]);

  return (
    <div ref={rowRef} className={styles.row} tabIndex={-1}>
      <div className={styles.head}>
        <span className={styles.labelGroup}>
          <span id={labelId} className={styles.label}>
            {label}
          </span>
          {unit ? <span className={styles.unit}>({unit})</span> : null}
          {help}
          {onReset && modified ? (
            <button
              type="button"
              className={styles.reset}
              aria-label={resetLabel ?? `${label} 기본값 복원`}
              title={resetLabel ?? "기본값 복원"}
              onClick={() => {
                setBodyKey((key) => key + 1);
                onReset();
                setFocusAfterReset(true);
              }}
            >
              <RotateCcw size={12} strokeWidth={2.2} aria-hidden="true" />
            </button>
          ) : null}
        </span>
        {control !== undefined ? (
          <div className={styles.control}>{control}</div>
        ) : editable ? (
          <button
            type="button"
            className={styles.toggle}
            data-slot="setting-toggle"
            aria-labelledby={`${labelId} ${valueId}`}
            aria-expanded={open}
            aria-controls={open ? bodyId : undefined}
            onClick={() => setOpen((current) => !current)}
          >
            <span id={valueId} className={styles.value}>
              {value}
            </span>
            <ChevronDown
              className={styles.chevron}
              size={14}
              strokeWidth={2}
              aria-hidden="true"
            />
          </button>
        ) : (
          <span className={`${styles.value} ${styles.staticValue}`}>{value}</span>
        )}
      </div>
      {caption ? <div className={styles.caption}>{caption}</div> : null}
      {editable && open ? (
        <div key={bodyKey} id={bodyId} className={styles.body}>
          {children}
        </div>
      ) : null}
    </div>
  );
}
