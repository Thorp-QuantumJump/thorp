import { useState } from 'react'
import { Square } from 'lucide-react'
import { cancelParamSearch, normalizeParamSearchError } from '@/api/paramSearch'
import type { ParamSearchCancelResult } from '@/types/paramSearch'
import { formatCount } from '@/utils/paramSearchLabels'
import styles from './CancelButton.module.css'

interface CancelButtonProps {
  searchId: string
  /** spec 014 이전의 임시 요청자 통로. 인증이 아니다. */
  requesterId?: number | null
  /** 취소가 받아들여진 뒤. 화면이 상태를 다시 읽도록 알린다. */
  onCancelled: (result: ParamSearchCancelResult) => void
}

/**
 * 탐색 취소(T107, FR-009).
 *
 * <p><b>되돌릴 수 없으므로 한 번 더 묻는다.</b> 눌린 즉시 발행하지 않은 후보가 '미실행'으로
 * 확정되고, 그 후보들은 재시도 대상도 아니다 — 실수로 눌렀을 때 되돌릴 방법이 없다.
 *
 * <p>취소해도 <b>이미 큐에 들어간 후보는 완주한다</b>. 그래서 성공 응답의 상태가 아직 `RUNNING`일
 * 수 있고, 그 사실을 결과 문장에 적는다. 적지 않으면 취소가 안 먹은 것처럼 보인다.
 */
export function CancelButton({ searchId, requesterId, onCancelled }: CancelButtonProps) {
  const [confirming, setConfirming] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ParamSearchCancelResult | null>(null)

  const submit = async () => {
    setPending(true)
    setError(null)
    try {
      const cancelled = await cancelParamSearch(searchId, requesterId)
      setResult(cancelled)
      setConfirming(false)
      onCancelled(cancelled)
    } catch (caught) {
      setError(normalizeParamSearchError(caught).message)
    } finally {
      setPending(false)
    }
  }

  if (result) {
    return (
      <p className={styles.done} role="status">
        {`미실행 ${formatCount(result.skipped)}개를 확정했습니다.`}
        {result.inFlight > 0
          ? ` 이미 실행에 들어간 ${formatCount(result.inFlight)}개는 끝까지 계산합니다.`
          : ' 남아서 도는 후보는 없습니다.'}
      </p>
    )
  }

  return (
    <div className={styles.wrap}>
      {confirming ? (
        <div className={styles.confirm}>
          <span>멈추면 아직 실행하지 않은 후보는 계산하지 않습니다. 되돌릴 수 없습니다.</span>
          <div className={styles.confirmActions}>
            <button
              type="button"
              className={styles.danger}
              onClick={() => void submit()}
              disabled={pending}
            >
              {pending ? '멈추는 중' : '멈추기'}
            </button>
            <button
              type="button"
              className={styles.secondary}
              onClick={() => setConfirming(false)}
              disabled={pending}
            >
              계속 실행
            </button>
          </div>
        </div>
      ) : (
        <button type="button" className={styles.trigger} onClick={() => setConfirming(true)}>
          <Square size={13} fill="currentColor" aria-hidden="true" />
          탐색 멈추기
        </button>
      )}

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
