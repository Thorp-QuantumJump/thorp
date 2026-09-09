import { RefreshCw } from 'lucide-react'
import styles from './MarketDataStatus.module.css'

interface MarketDataStatusProps {
  failedCount: number
  isRefreshing: boolean
  onRetry: () => void
}

/** 일부 지표 연결 실패를 알리고 전체 최신값을 즉시 다시 조회할 수 있게 한다. */
export function MarketDataStatus({
  failedCount,
  isRefreshing,
  onRetry,
}: MarketDataStatusProps) {
  if (failedCount === 0) return null

  return (
    <div className={styles.notice} role="status" aria-live="polite">
      <span>{failedCount}개 지표 연결 실패 · 예시값 표시 중</span>
      <button type="button" onClick={onRetry} disabled={isRefreshing}>
        <RefreshCw size={13} aria-hidden="true" />
        {isRefreshing ? '다시 불러오는 중' : '다시 불러오기'}
      </button>
    </div>
  )
}
