import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import styles from './AuthForm.module.css'

type AuthFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'aria-invalid'> & {
  label: string
  hint?: ReactNode
  error?: string
}

/** 인증 화면에서 공통으로 쓰는 label·hint·error가 연결된 입력 필드다. */
export const AuthField = forwardRef<HTMLInputElement, AuthFieldProps>(function AuthField(
  { label, hint, error, id: providedId, className, ...inputProps },
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
      <input
        {...inputProps}
        ref={ref}
        id={id}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
      />
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
