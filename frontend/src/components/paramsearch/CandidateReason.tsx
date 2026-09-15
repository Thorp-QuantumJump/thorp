import type { SearchCandidateItem } from '@/types/paramSearch'
import { candidateStatusText, missingPerformanceReason } from '@/utils/paramSearchLabels'
import styles from './CandidateReason.module.css'

interface CandidateReasonProps {
  candidate: SearchCandidateItem
}

/** 성과가 아예 없는 상태들. 이 셋은 "수익이 낮았다"와 전혀 다른 이야기다. */
const unmeasured = ['EXCLUDED', 'FAILED', 'SKIPPED']

/**
 * 후보별 제외·실패·미실행 사유(T106, FR-011, 원칙 VI).
 *
 * <p><b>낮은 수익과 구분하는 것이 이 컴포넌트의 전부다.</b> 숫자 자리를 비워 두기만 하면 사용자는
 * "왜 비었지"를 각자 상상하고, 0으로 채우면 "수익이 없었다"로 읽는다. 둘 다 사실이 아니다 —
 * 이 후보들은 <b>계산을 하지 않았다</b>.
 *
 * <p>사유 문장은 서버가 준 것을 먼저 쓴다. 유효성 규칙이 돌려준 문장이 화면이 지어낸 문장보다
 * 정확하고, 규칙이 늘어도 화면을 고치지 않아도 된다.
 */
export function CandidateReason({ candidate }: CandidateReasonProps) {
  const notMeasured = unmeasured.includes(candidate.status)

  return (
    <p className={styles.reason} data-unmeasured={notMeasured}>
      <strong>{candidateStatusText(candidate.status)}</strong>
      <span>{missingPerformanceReason(candidate)}</span>
      {notMeasured && (
        <span className={styles.distinction}>
          계산하지 않았다는 뜻이며 수익이 낮았다는 뜻이 아닙니다.
        </span>
      )}
    </p>
  )
}
