import { ChevronDown } from "lucide-react";
import { useId, useState, type ReactNode } from "react";
import styles from "./SettingRow.module.css";

interface SettingGroupProps {
  /** 묶음 제목('분석 조건', '전략 설정'). 영역 이름으로도 쓴다 */
  title: string;
  /** 제목 오른쪽 끝의 행동('모두 기본값 복원'). 없으면 비운다 */
  action?: ReactNode;
  /** 제목 아래, 행 목록 위에 두는 한두 문장 */
  note?: ReactNode;
  /** 행 목록에 붙일 접근 가능 이름. 값을 한데 읽는 자리라 요약으로 부른다 */
  rowsLabel?: string;
  children: ReactNode;
  /** 행 목록 아래에 늘 보이는 것(규칙 위반 안내) */
  footer?: ReactNode;
}

/**
 * 설정 항목 행들을 묶는 자리다. 제목 한 줄과 행 목록으로 이루어진다.
 *
 * <p>묶음 자체는 접히지 않는다. 항목마다 지금 값을 한 줄로 보이고 그 항목만 펼쳐 고치므로, 묶음을 통째로
 * 여닫는 단추가 있으면 여는 자리가 두 겹이 된다.
 */
export function SettingGroup({
  title,
  action,
  note,
  rowsLabel,
  children,
  footer,
}: SettingGroupProps) {
  return (
    <section className={styles.group} aria-label={title}>
      <div className={styles.groupHead}>
        <h3 className={styles.groupTitle}>{title}</h3>
        {action}
      </div>
      {note}
      <div className={styles.rows} aria-label={rowsLabel}>
        {children}
      </div>
      {footer}
    </section>
  );
}

interface SettingRowProps {
  label: string;
  /** 이름 옆 작은 단위 표기. 목록형 축의 단위가 여기 온다 */
  unit?: string;
  /** 이름 옆 도움말(InlineHelp·TermHelp). 펼침 단추 밖에 두어 도움말을 열어도 행이 여닫히지 않는다 */
  help?: ReactNode;
  /** 지금 적용되는 값. 접혀 있어도 늘 보인다 */
  value: ReactNode;
  /** 펼쳤을 때의 편집 폼(또는 읽기 전용 상세). 없으면 여닫는 단추가 없는 읽기 전용 행이다 */
  children?: ReactNode;
}

/**
 * 설정 항목 한 행(FR-002·FR-003, SC-005).
 *
 * <p><b>'항목 | 지금 값 | ▾' 한 줄이 곧 요약이고 여는 자리다.</b> 요약 줄과 편집 폼을 따로 두면 같은 값이
 * 화면 두 곳에서 말해지고, 여러 항목을 한 아코디언에 넣으면 바꾸려는 항목 하나를 위해 전부 펼쳐야 한다.
 * 값은 접혀 있어도 늘 보이고(FR-002), 입력 요소는 펼친 항목에만 생긴다(SC-005).
 *
 * <p><b>펼침 단추의 이름은 항목 이름이다.</b> 보이는 글자는 값이지만, 이 단추가 하는 일은 그 항목을 고치는
 * 자리를 여는 것이다. 도움말은 단추 밖 이름 옆에 두어 도움말을 열어도 행이 여닫히지 않는다.
 *
 * <p>읽기 전용 행(비교 대상·운용 방식)은 같은 모양에서 단추와 화살표만 없다.
 */
export function SettingRow({ label, unit, help, value, children }: SettingRowProps) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const editable = children !== undefined && children !== null;

  return (
    <div className={styles.row} data-open={editable ? open : undefined}>
      <div className={styles.head}>
        <span className={styles.labelGroup}>
          <span className={styles.label}>{label}</span>
          {unit ? <span className={styles.unit}>({unit})</span> : null}
          {help}
        </span>
        {editable ? (
          <button
            type="button"
            className={styles.toggle}
            data-slot="setting-toggle"
            aria-label={label}
            aria-expanded={open}
            aria-controls={open ? bodyId : undefined}
            onClick={() => setOpen((current) => !current)}
          >
            <span className={styles.value}>{value}</span>
            <ChevronDown
              className={styles.chevron}
              size={16}
              strokeWidth={1.8}
              aria-hidden="true"
            />
          </button>
        ) : (
          <span className={`${styles.value} ${styles.staticValue}`}>{value}</span>
        )}
      </div>
      {editable && open ? (
        <div id={bodyId} className={styles.body}>
          {children}
        </div>
      ) : null}
    </div>
  );
}
