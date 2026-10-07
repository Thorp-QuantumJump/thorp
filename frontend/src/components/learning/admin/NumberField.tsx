import { useId, useState } from 'react'

/**
 * 숫자 칸 하나. 치고 있는 글자를 확정된 값과 따로 둔다(`ExampleEditor`의 비중 칸과 같은 방식, 013 T089).
 *
 * <p>확정된 값을 글자마다 다시 그리면 '-'나 '1.'처럼 숫자가 되기 전의 글자가 지워져 음수·소수를 칠 수 없다.
 * 숫자로 읽히는 글자만 올려 보낸다.
 *
 * <p><b>받지 않은 입력은 말없이 버리지 않는다.</b> 숫자로 읽을 수 없으면 그 칸 옆에 이유와 지금 값을 한 문장으로
 * 알린다(constitution 원칙 VI). 칸을 벗어나면 확정된 값의 표시로 돌아간다. 빈 칸과 '-'는 아직 치는 중으로 보고
 * 알리지 않는다.
 */

interface NumberFieldProps {
  label: string
  value: number
  onCommit: (value: number) => void
  /** 정수만 받는가. 단계 안 순서·읽는 순서는 정수다 */
  integer?: boolean
  className?: string
}

function isTyping(trimmed: string): boolean {
  return trimmed === '' || trimmed === '-'
}

function rejectionOf(trimmed: string, integer: boolean, current: number): string | null {
  if (isTyping(trimmed)) return null
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed)) {
    return `숫자로 읽을 수 없어 반영하지 않았습니다. 지금 값은 ${current}입니다.`
  }
  if (integer && !Number.isInteger(parsed)) {
    return `정수만 받아 반영하지 않았습니다. 지금 값은 ${current}입니다.`
  }
  return null
}

export function NumberField({
  label,
  value,
  onCommit,
  integer = false,
  className,
}: NumberFieldProps) {
  const inputId = useId()
  const errorId = useId()
  /** `null`이면 치는 중이 아니며 화면은 확정된 값을 보인다 */
  const [text, setText] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const accept = (raw: string) => {
    setText(raw)
    const trimmed = raw.trim()
    const rejection = rejectionOf(trimmed, integer, value)
    setError(rejection)
    if (rejection === null && !isTyping(trimmed)) onCommit(Number(trimmed))
  }

  const settle = () => {
    setText(null)
    setError(null)
  }

  return (
    <div className={className}>
      <label htmlFor={inputId}>{label}</label>
      <input
        id={inputId}
        type="text"
        inputMode={integer ? 'numeric' : 'decimal'}
        value={text ?? String(value)}
        onChange={(event) => accept(event.target.value)}
        onBlur={settle}
        aria-invalid={error === null ? undefined : true}
        aria-describedby={error === null ? undefined : errorId}
      />
      {error === null ? null : (
        <span id={errorId} role="alert">
          {error}
        </span>
      )}
    </div>
  )
}
