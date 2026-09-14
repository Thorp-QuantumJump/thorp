import styles from './ProvisionalBadge.module.css'

interface ProvisionalBadgeProps {
  /** 배지가 붙은 값의 이름. 보조기술이 "무엇이 임시값인지" 알 수 있어야 한다. */
  label: string
}

/**
 * 아직 실측하지 않아 임시로 정한 값에 붙이는 표시(FR-005a, 원칙 IX).
 *
 * <p>어떤 항목이 임시값인지는 화면이 정하지 않는다 — `limits.provisional`에 든 항목에만 붙인다.
 * 실측이 끝나 서버가 그 목록에서 빼면 배지도 저절로 사라진다.
 *
 * <p>색만으로 알리지 않는다. "임시값"이라는 글자가 배지의 내용이다.
 */
export function ProvisionalBadge({ label }: ProvisionalBadgeProps) {
  return (
    <span className={styles.badge}>
      임시값
      <span className={styles.srOnly}>{` — ${label}은(는) 실측 전 임시로 정한 값입니다`}</span>
    </span>
  )
}
