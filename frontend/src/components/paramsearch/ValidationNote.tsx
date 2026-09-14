import { ShieldQuestion } from 'lucide-react'
import { ValidationBadge } from './ValidationBadge'
import type { ValidationLabel } from '@/types/paramSearch'
import styles from './ValidationNote.module.css'

interface ValidationNoteProps {
  label: ValidationLabel
  /** 서버가 준 한 문장. 있으면 그대로 쓴다 — 화면이 사유를 지어내지 않는다. */
  note?: string | null
}

/**
 * 미검증 안내(T115, FR-016).
 *
 * <p>세 가지를 <b>그 낱말 그대로</b> 적는다 — <b>미검증 · 개선 근거 없음 · 판단 보류</b>. 셋은
 * 서로 다른 말이다. "미검증"은 확인하지 않았다는 것이고, "개선 근거 없음"은 기준보다 나아졌다고
 * 말할 수 없다는 것이며, "판단 보류"는 그래서 우리가 결론을 내지 않는다는 것이다.
 *
 * <p><b>기능을 막지는 않는다.</b> 결과를 숨기거나 버튼을 잠그면 사용자는 무엇이 있었는지도 모르게
 * 된다. 우리가 하는 일은 보이되 <b>무엇이라고 말할 수 없는지</b>를 함께 보이는 것이다(원칙 III).
 */
export function ValidationNote({ label, note }: ValidationNoteProps) {
  if (label !== 'UNVALIDATED') {
    return (
      <p className={styles.note}>
        <ShieldQuestion size={15} aria-hidden="true" />
        <span>
          <ValidationBadge label={label} />
          {note ? <span className={styles.text}>{note}</span> : null}
        </span>
      </p>
    )
  }

  return (
    <div className={styles.unvalidated}>
      <ShieldQuestion size={16} aria-hidden="true" />
      <div>
        <p className={styles.headline}>미검증 · 개선 근거 없음 · 판단 보류</p>
        <p className={styles.text}>
          {note ??
            '평가 분할이 등록되지 않아 이 결과가 기준보다 나아졌다고 말할 수 없습니다.'}
        </p>
        <p className={styles.text}>
          결과는 그대로 보이고 다른 기능도 막지 않습니다. 다만 이 값을 우월한 설정의 근거로 쓰지
          말아 주세요.
        </p>
      </div>
    </div>
  )
}
