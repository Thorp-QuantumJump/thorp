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

/** 결과 의미를 바꾸는 상태를 결과 옆 한 문장으로 보인다(FR-013). 알릴 상태가 없으면 아무것도 그리지 않는다. */
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

  const sentence = selectStatusSentence(detail, completedThrough)
  const retryable = detail.status === 'FAILED' && Boolean(onRetry)

  // 보일 것이 하나도 없으면 상자째 그리지 않는다. `.line`이 테두리·배경·안쪽 여백을 가지고 있어,
  // 안이 비어도 그래프 위에 빈 상자가 남는다. 빈 `role="status"`도 화면 낭독기에 아무것도 없는
  // 알림 영역을 만든다.
  if (!sentence && !retryable && !retryError) return null

  return (
    <div className={styles.line}>
      {sentence ? (
        <p className={styles.sentence} role="status">
          {sentence}
        </p>
      ) : null}
      {retryable ? (
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
