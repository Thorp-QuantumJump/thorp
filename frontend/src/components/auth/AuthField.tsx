import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import styles from './AuthForm.module.css'

type AuthFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'aria-invalid'> & {
  label: string
  hint?: ReactNode
  error?: string
  /**
   * 입력칸 오른쪽에 붙는 버튼('인증번호 받기'처럼 그 칸의 값으로 무언가를 하는 것).
   *
   * <p><b>필드 바깥에 형제로 두면 오류가 뜰 때 어긋난다.</b> 도움말·오류 문구는 입력칸 아래
   * 같은 상자 안에 쌓이므로, 바깥에서 아래쪽을 맞춰 둔 버튼은 문구가 생긴 만큼 밀려 내려가
   * 입력칸과 줄이 틀어진다. 입력칸과 한 줄에 묶어 두면 문구가 늘든 줄든 흔들리지 않는다.
   */
  action?: ReactNode
}

/** 인증 화면에서 공통으로 쓰는 label·hint·error가 연결된 입력 필드다. */
export const AuthField = forwardRef<HTMLInputElement, AuthFieldProps>(function AuthField(
  { label, hint, error, action, id: providedId, className, ...inputProps },
  ref,
) {
  const generatedId = useId()
  const id = providedId ?? generatedId
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div className={`${styles.field} ${className ?? ''}`.trim()}>
      <label htmlFor={id}>{label}</label>
      <div className={action ? styles.controlRow : undefined}>
        <input
          {...inputProps}
          ref={ref}
          id={id}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy}
        />
        {action}
      </div>
      {hint ? (
        <small id={hintId} className={styles.hint}>
          {hint}
        </small>
      ) : null}
      {error ? (
        <small id={errorId} className={styles.fieldError}>
          {error}
        </small>
      ) : null}
    </div>
  )
})
