import { useId } from 'react'
import { ALL_RUNS, parseRichText, richTextLength, type AllowedRuns } from './richTextSyntax'
import styles from './AdminEditor.module.css'

/**
 * 글자 조각 필드 하나를 편집 문법 문자열로 편집한다(013 research R26, T168).
 *
 * <p><b>값은 문법 문자열 그대로다.</b> 조각으로 바꾸는 일은 저장할 때 한 번 한다(`editableDraft.ts`).
 * 칠 때마다 조각으로 바꿔 두면 쓰다 만 `{{`가 사라지거나 다른 글자로 바뀐다.
 *
 * <p><b>틀린 문법은 줄·열 위치로 바로 보이고 입력은 막지 않는다.</b> 저장은 오류가 없어질 때까지 막힌다.
 * 길이는 안내만 하고 판정은 서버가 한다(계약 §5).
 */

interface RichTextFieldProps {
  label: string
  value: string
  onChange: (value: string) => void
  /** 이 칸이 허용하는 조각(data-model §5-3). 기본은 글자·용어·출처 모두다 */
  allow?: AllowedRuns
  /** 서버의 글자 수 상한. 안내 문장에만 쓴다 */
  maxLength?: number
  rows?: number
}

function allowedText(allow: AllowedRuns): string {
  const kinds = [
    allow.term ? '{{용어:식별자|글자}}' : null,
    allow.source ? '{{출처:이름|주소}}' : null,
  ]
  const usable = kinds.filter((kind) => kind !== null)
  return usable.length === 0 ? '글자만' : `글자, ${usable.join(', ')}`
}

export function RichTextField({
  label,
  value,
  onChange,
  allow = ALL_RUNS,
  maxLength,
  rows = 3,
}: RichTextFieldProps) {
  const issuesId = useId()
  const parsed = parseRichText(value, allow)
  const length = parsed.ok ? richTextLength(parsed.runs) : null

  return (
    <div className={styles.field}>
      <label className={styles.field}>
        <span>
          {label}
          {maxLength === undefined ? '' : ` (최대 ${maxLength}자`}
          {maxLength === undefined ? '' : length === null ? ')' : `, 지금 ${length}자)`}
        </span>
        <textarea
          rows={rows}
          value={value}
          aria-invalid={!parsed.ok}
          aria-describedby={parsed.ok ? undefined : issuesId}
          title={`쓸 수 있는 것: ${allowedText(allow)}`}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
      {parsed.ok ? null : (
        <ul id={issuesId} className={styles.issues}>
          {parsed.issues.map((issue, index) => (
            <li key={index}>
              {issue.line}줄 {issue.column}칸: {issue.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
