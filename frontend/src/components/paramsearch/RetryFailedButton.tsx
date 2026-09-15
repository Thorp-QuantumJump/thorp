import { useState } from 'react'
import { RotateCcw } from 'lucide-react'
import { normalizeParamSearchError, retryFailedCandidates } from '@/api/paramSearch'
import type { ParamSearchRetryResult } from '@/types/paramSearch'
import { formatCount } from '@/utils/paramSearchLabels'
import styles from './RetryFailedButton.module.css'

interface RetryFailedButtonProps {
  searchId: string
  /** 지금 실패로 남은 후보 수. **0이면 아무것도 그리지 않는다.** */
  failedCount: number
  /** spec 014 이전의 임시 요청자 통로. 인증이 아니다. */
  requesterId?: number | null
  /** 재시도가 접수된 뒤. 화면이 상태를 다시 읽도록 알린다. */
  onRetried: (result: ParamSearchRetryResult) => void
}

/**
 * 실패 후보 재시도(T107a, FR-024).
 *
 * <p><b>실패가 있을 때만 보인다.</b> 언제나 떠 있으면 "다시 돌려 볼 수 있다"는 인상이 생기는데,
 * 이것은 같은 조건을 다시 계산하는 것이지 조건을 바꿔 다시 실험하는 것이 아니다. 기준을 바꿔
 * 돌리는 것은 재시도가 아니라 새 탐색이다(FR-013c).
 *
 * <p><b>한 번에 다 보내지 않는다.</b> 발행 창을 넘겨 보내지 않으므로 실패가 창보다 많으면 남는다.
 * 그 수를 그대로 보이고 다시 누르게 한다 — 자동으로 이어 보내면 사용자가 멈출 자리가 없다.
 *
 * <p>미실행(`SKIPPED`) 후보는 대상이 아니다. 취소·한도로 빠진 것을 되살리면 "어디까지 돌렸는가"가
 * 뜻을 잃는다.
 */
export function RetryFailedButton({
  searchId,
  failedCount,
  requesterId,
  onRetried,
}: RetryFailedButtonProps) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ParamSearchRetryResult | null>(null)

  if (failedCount <= 0) return null

  const submit = async () => {
    setPending(true)
    setError(null)
    try {
      const retried = await retryFailedCandidates(searchId, requesterId)
      setResult(retried)
      onRetried(retried)
    } catch (caught) {
      setError(normalizeParamSearchError(caught).message)
    } finally {
      setPending(false)
    }
  }

  return (
    <div className={styles.wrap}>
      <div className={styles.row}>
        <span className={styles.count}>{`실패한 후보 ${formatCount(failedCount)}개`}</span>
        <button
          type="button"
          className={styles.retry}
          onClick={() => void submit()}
          disabled={pending}
        >
          <RotateCcw size={14} aria-hidden="true" />
          {pending ? '다시 보내는 중' : '실패한 후보 다시 시도'}
        </button>
      </div>

      <p className={styles.note}>
        같은 조건으로 다시 계산합니다. 참조 버전·탐색 범위·선정 기준은 처음 그대로이고 새 묶음을
        만들지 않습니다.
      </p>

      {result && (
        <p className={styles.done} role="status">
          {`${formatCount(result.retried)}개를 다시 보냈습니다.`}
          {result.remainingFailed > 0
            ? ` 한 번에 보낼 수 있는 수를 넘어 ${formatCount(result.remainingFailed)}개가 남았습니다. 다시 눌러 주세요.`
            : ' 남은 실패 후보는 없습니다.'}
        </p>
      )}

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
