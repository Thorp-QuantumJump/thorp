import { Button } from '@/components/ui/button'
import type { CompareTargetState } from '@/hooks/useCompareTarget'
import { strategyLabel } from './conditionLine'
import { ResultStatusLine } from './ResultStatusLine'
import styles from './comparison.module.css'

export function CompareTargetSlot({
  state,
  onRetry,
  onClear,
  onPick,
}: {
  state: CompareTargetState
  onRetry: () => void
  onClear: () => void
  onPick: () => void
}) {
  if (state.targetState === 'NONE') return null
  const detail = state.detail
  const progress = detail?.progress
  const completedThrough =
    progress?.lastTradingDate ??
    (state.timeseries.status === 'loaded'
      ? (state.timeseries.data.points[state.timeseries.data.points.length - 1]?.date ?? null)
      : null)
  const stages: Record<string, string> = {
    PREPARING_DATA: '자료 준비',
    COMPUTING: '분석 계산',
    SAVING: '결과 저장',
  }
  return (
    <section className={styles.panel} aria-label="비교 대상 상태">
      <div role="status" aria-live="polite">
        {state.targetState === 'REQUESTED' ? <p>비교 결과를 요청하고 있습니다.</p> : null}
        {state.targetState === 'RUNNING' ? (
          <p>
            {detail?.status === 'QUEUED'
              ? '비교 분석이 차례를 기다리고 있습니다.' +
                (detail.queueWaitSeconds !== null
                  ? ' 지금까지 ' + detail.queueWaitSeconds + '초 기다렸습니다.'
                  : '')
              : (stages[progress?.stage ?? ''] ?? '분석 진행') +
                (progress?.completedTradingDays != null && progress.totalTradingDays
                  ? ': ' +
                    progress.completedTradingDays +
                    '/' +
                    progress.totalTradingDays +
                    '거래일 (' +
                    Math.round((progress.completedTradingDays / progress.totalTradingDays) * 100) +
                    '%)'
                  : '')}
          </p>
        ) : null}
        {state.targetState === 'EXECUTION_LIMIT' ? (
          <p>
            실행 한도에 걸렸습니다.{' '}
            {state.retryAfterSeconds !== null
              ? state.retryAfterSeconds + '초 뒤 다시 요청할 수 있습니다.'
              : state.message}
          </p>
        ) : null}
        {state.targetState === 'FAILED' ? (
          <p>{state.message ?? '비교 분석을 완료하지 못했습니다.'}</p>
        ) : null}
        {state.targetState === 'EXPIRED' ? (
          <p>비교 대상 결과가 더 이상 남아 있지 않습니다. 기준 결과는 계속 볼 수 있습니다.</p>
        ) : null}
        {state.targetState === 'READY' && detail ? <p>비교 대상: {strategyLabel(detail)}</p> : null}
      </div>
      {state.targetState === 'READY' && detail ? (
        <ResultStatusLine detail={detail} completedThrough={completedThrough} />
      ) : null}
      <div className={styles.actions}>
        {state.retryable &&
        (state.targetState === 'FAILED' || state.targetState === 'EXECUTION_LIMIT') ? (
          <Button variant="outline" onClick={onRetry}>
            {state.targetState === 'EXECUTION_LIMIT' ? '다시 요청' : '다시 시도'}
          </Button>
        ) : null}
        {state.targetState === 'EXPIRED' ? (
          <Button variant="outline" onClick={onPick}>
            다시 고르기
          </Button>
        ) : null}
        <Button variant="outline" onClick={onClear}>
          비교 해제
        </Button>
      </div>
    </section>
  )
}
