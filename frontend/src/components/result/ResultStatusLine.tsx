import { useState } from 'react'
import type { PublicResultDetail } from '@/types/result'
import { Button } from '@/components/ui/button'
import { selectStatusSentence } from './statusSentences'
import styles from './ResultStatusLine.module.css'

type ResultStatusLineProps = {
  detail: PublicResultDetail
  completedThrough: string | null
  /** 실패 결과를 사용자가 명시적으로 다시 요청한다. 비교 대상도 같은 컴포넌트를 재사용한다 */
  onRetry?: () => void | Promise<void>
}

/** 결과 의미를 바꾸는 상태와 검증 구분을 결과 옆 한 문장으로 보인다(FR-012~FR-013). */
export function ResultStatusLine({
  detail,
  completedThrough,
  onRetry,
}: ResultStatusLineProps) {
  const [retrying, setRetrying] = useState(false)
  const [retryError, setRetryError] = useState<string | null>(null)

  const retry = async () => {
    if (!onRetry || retrying) return
    setRetrying(true)
    setRetryError(null)
    try {
      await onRetry()
    } catch (error) {
      setRetryError(error instanceof Error ? error.message : '다시 시도하지 못했습니다.')
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div className={styles.line}>
      <p className={styles.sentence} role="status">
        {selectStatusSentence(detail, completedThrough)}
      </p>
      {detail.status === 'FAILED' && onRetry ? (
        <Button type="button" variant="outline" disabled={retrying} onClick={() => void retry()}>
          {retrying ? '다시 요청하는 중' : '다시 시도'}
        </Button>
      ) : null}
      {retryError ? (
        <p className={styles.retryError} role="alert">
          {retryError}
        </p>
      ) : null}
    </div>
  )
}
