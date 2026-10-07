import { useState } from 'react'

/**
 * 숫자 칸 하나. 치고 있는 글자를 확정된 값과 따로 둔다(`ExampleEditor`의 비중 칸과 같은 방식).
 *
 * <p>확정된 값을 글자마다 다시 그리면 '-'나 '1.'처럼 숫자가 되기 전의 글자가 지워져 음수·소수를 칠 수 없다.
 * 숫자로 읽히는 글자만 올려 보내고, 칸을 떠나면 확정된 값으로 되돌린다.
 */

interface NumberFieldProps {
  label: string
  value: number
  onCommit: (value: number) => void
  /** 정수만 받는가. 단계 안 순서·읽는 순서는 정수다 */
  integer?: boolean
  className?: string
}

export function NumberField({
  label,
  value,
  onCommit,
  integer = false,
  className,
}: NumberFieldProps) {
  const [text, setText] = useState<string | null>(null)

  const accept = (raw: string) => {
    setText(raw)
    const trimmed = raw.trim()
    if (trimmed === '' || trimmed === '-') return
    const parsed = Number(trimmed)
    if (!Number.isFinite(parsed) || (integer && !Number.isInteger(parsed))) return
    onCommit(parsed)
  }

  return (
    <label className={className}>
      <span>{label}</span>
      <input
        type="text"
        inputMode={integer ? 'numeric' : 'decimal'}
        value={text ?? String(value)}
        onChange={(event) => accept(event.target.value)}
        onBlur={() => setText(null)}
      />
    </label>
  )
}
