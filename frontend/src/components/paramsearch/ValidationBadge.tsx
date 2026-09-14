import type { ValidationLabel } from '@/types/paramSearch'
import { validationLabelText } from '@/utils/paramSearchLabels'
import styles from './ValidationBadge.module.css'

interface ValidationBadgeProps {
  label: ValidationLabel
  /** 무엇에 붙은 배지인지. 보조기술이 배지만 읽고 대상을 잃지 않게 한다. */
  describes?: string
}

/**
 * 검증 상태 배지(T114, plan 설계 제약 2, FR-015).
 *
 * <p><b>결과가 보이는 곳마다 함께 보인다.</b> 배지를 결과와 떼어 놓으면 숫자만 읽고 넘어가는
 * 경로가 생기고, 그 순간 미검증 결과가 검증된 결과처럼 읽힌다.
 *
 * <p>라벨을 각 화면이 직접 그리지 않고 이 컴포넌트를 쓰는 이유는 <b>빠뜨림을 찾을 수 있게</b>
 * 하기 위해서다 — 한 곳에 모여 있어야 "어디에 안 붙었는지"를 검사할 수 있다.
 *
 * <p>색으로만 구분하지 않는다. 배지의 내용은 글자이고 색은 거들 뿐이다.
 */
export function ValidationBadge({ label, describes }: ValidationBadgeProps) {
  return (
    <span className={styles.badge} data-label={label}>
      {validationLabelText(label)}
      {describes ? <span className={styles.srOnly}>{` — ${describes}`}</span> : null}
    </span>
  )
}
