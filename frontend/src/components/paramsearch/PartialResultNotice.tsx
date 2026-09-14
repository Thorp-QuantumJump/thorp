import { CircleAlert } from 'lucide-react'
import type { ParamSearchStopReason, SearchCounts } from '@/types/paramSearch'
import { formatCount, stopReasonText } from '@/utils/paramSearchLabels'
import styles from './PartialResultNotice.module.css'

interface PartialResultNoticeProps {
  counts: SearchCounts | null
  stopReason: ParamSearchStopReason | null
  /** 서버가 준 한 문장. 있으면 그대로 쓴다. */
  partialNote?: string | null
  /** 아직 도는 후보가 있는가 — 취소를 눌렀지만 묶음이 끝나지 않은 상태. */
  stopping?: boolean
}

/**
 * 부분 결과 안내(T105, FR-009).
 *
 * <p><b>"완료한 범위"를 숫자로 명시한다.</b> 부분 결과라는 말만 하고 몇 개까지 돌았는지 밝히지
 * 않으면, 사용자는 지금 보는 결과가 전체의 얼마인지 모른 채 비교하게 된다.
 *
 * <p>미실행은 <b>실패가 아니다</b>. 취소·한도로 발행하지 않은 것이라 "안 좋아서 빠진 것"이 아니고,
 * 그래서 실패 수와 따로 센다.
 *
 * <p>멈추는 중일 때도 그 사실을 적는다 — 이미 큐에 든 후보는 완주하므로 취소를 누른 뒤에도 숫자가
 * 잠시 더 움직인다. 모르면 취소가 안 먹은 것처럼 보인다.
 */
export function PartialResultNotice({
  counts,
  stopReason,
  partialNote,
  stopping = false,
}: PartialResultNoticeProps) {
  return (
    <section className={styles.notice} aria-labelledby="partial-title">
      <CircleAlert size={16} aria-hidden="true" />
      <div>
        <p id="partial-title" className={styles.headline}>
          {stopping ? '멈추는 중입니다' : '부분 결과입니다'}
          {stopReason ? <span className={styles.reason}>{stopReasonText(stopReason)}</span> : null}
        </p>

        {counts && (
          <dl className={styles.counts}>
            <div>
              <dt>유효 후보</dt>
              <dd>{formatCount(counts.valid)}</dd>
            </div>
            <div>
              <dt>완료</dt>
              <dd>{formatCount(counts.completed)}</dd>
            </div>
            <div>
              <dt>실패</dt>
              <dd>{formatCount(counts.failed)}</dd>
            </div>
            <div>
              <dt>미실행</dt>
              <dd>{formatCount(counts.skipped)}</dd>
            </div>
          </dl>
        )}

        {partialNote && <p className={styles.text}>{partialNote}</p>}

        <p className={styles.text}>
          {stopping
            ? '이미 실행에 들어간 후보는 끝까지 계산합니다. 그것이 끝나면 부분 결과로 마무리됩니다.'
            : '미실행 후보는 계산하지 않았을 뿐 성과가 나쁜 것이 아닙니다. 남은 범위는 새 탐색으로 이어서 볼 수 있습니다.'}
        </p>
      </div>
    </section>
  )
}
