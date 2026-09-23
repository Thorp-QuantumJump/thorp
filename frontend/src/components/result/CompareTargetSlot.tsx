import { Button } from '@/components/ui/button'
import type { CompareTargetState } from '@/hooks/useCompareTargets'
import { ResultStatusLine } from './ResultStatusLine'
import styles from './comparison.module.css'

/** 기준과 겹쳐 그리지 않는 이유를 한 문장으로(FR-005). 값을 계산하지 않고 어긋난 항목만 말한다. */
export const MISMATCH_MESSAGES = {
  conditions:
    '섹터·금액·비용·자료 기준 또는 분석 시나리오가 달라 기준과 겹쳐 그리지 않았습니다. 비교 대상을 다시 골라 주세요.',
  period: '평가 기간 또는 준비 기간이 달라 기준과 겹쳐 그리지 않았습니다.',
  versions: '자료 또는 계산 엔진 기준이 달라 기준과 겹쳐 그리지 않았습니다. 같은 현재 기준으로 다시 계산해 비교할 수 있습니다.',
} as const

/**
 * 비교 대상 하나의 자리(FR-004, `S15P21E205-419`). 대상이 여럿이면 자리도 여럿이라 각자 다시 시도·해제할 수 있다.
 *
 * <p>`name`은 부른 쪽이 정한다 — 주소에 오른 대상은 결과의 전략 이름, 접수 중인 후보는 전략 이름과 설정값이다. 이 컴포넌트는
 * 이름을 짓지 않는다.
 */
export function CompareTargetSlot({
  state,
  name,
  onRetry,
  onRemove,
}: {
  state: CompareTargetState
  name: string
  onRetry: () => void
  onRemove: () => void
}) {
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
    <section className={styles.panel} aria-label={'비교 대상 ' + name}>
      <p className={styles.slotName}>{name}</p>
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
        {state.mismatch ? <p>{MISMATCH_MESSAGES[state.mismatch]}</p> : null}
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
        <Button variant="outline" onClick={onRemove}>
          비교 해제
        </Button>
      </div>
    </section>
  )
}
