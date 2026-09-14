import { CircleAlert, CircleCheck, Info } from 'lucide-react'
import styles from './AuthForm.module.css'

type AuthStatusProps = {
  message: string
  tone?: 'info' | 'success' | 'error'
}

/** 요청 결과를 시각적 색상과 보조 기술용 live region으로 함께 전달한다. */
export function AuthStatus({ message, tone = 'info' }: AuthStatusProps) {
  if (!message) return null

  const Icon = tone === 'success' ? CircleCheck : tone === 'error' ? CircleAlert : Info

  return (
    <div
      className={styles.status}
      data-tone={tone}
      role={tone === 'error' ? 'alert' : 'status'}
      aria-live={tone === 'error' ? 'assertive' : 'polite'}
      aria-atomic="true"
    >
      <Icon size={17} aria-hidden="true" />
      <span>{message}</span>
    </div>
  )
}
