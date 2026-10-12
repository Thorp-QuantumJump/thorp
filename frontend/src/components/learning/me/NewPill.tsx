import styles from './NewPill.module.css'

/**
 * 7일 동안 붙는 '새' 알약(013 FR-030, design-notes §4). 닫는 단추나 '봤음' 기록이 없다. 화면 낭독기는 '새로 받음'처럼
 * 뜻으로 읽는다. 'NEW'라고 쓰지 않는다.
 */
export function NewPill({ spoken = '새로 받음' }: { spoken?: string }) {
  return (
    <span className={styles.pill}>
      <span aria-hidden="true">새</span>
      <span className="sr-only">{spoken}</span>
    </span>
  )
}
