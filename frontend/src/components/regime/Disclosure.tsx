import type { ReactNode } from 'react'
import styles from './Disclosure.module.css'

interface DisclosureProps {
  label: string
  context: string
  tone?: 'neutral' | 'warning' | 'error'
  children: ReactNode
}

/** 신뢰 범위 표시는 모두 화면에 열어 두고 키보드와 보조기술로 읽을 수 있게 한다. */
export function Disclosure({ label, context, tone = 'neutral', children }: DisclosureProps) {
  return (
    <section
      className={`${styles.disclosure} ${styles[tone]}`}
      tabIndex={0}
      role="status"
      aria-label={`${context}: ${label}`}
      aria-live="polite"
    >
      <strong>{label}</strong>
      <div>{children}</div>
    </section>
  )
}
