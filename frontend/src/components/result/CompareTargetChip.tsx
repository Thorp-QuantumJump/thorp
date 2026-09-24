import { X } from 'lucide-react'
import type { CompareTargetState } from '@/hooks/useCompareTargets'
import styles from './comparison.module.css'

/** 기준과 겹쳐 그리지 않는 이유를 한 문장으로(FR-005). 값을 계산하지 않고 어긋난 항목만 말한다. */
export const MISMATCH_MESSAGES = {
  conditions:
    '섹터·금액·비용·자료 기준 또는 분석 시나리오가 달라 기준과 겹쳐 그리지 않았습니다. 비교 대상을 다시 골라 주세요.',
  period: '평가 기간 또는 준비 기간이 달라 기준과 겹쳐 그리지 않았습니다.',
  versions:
    '자료 또는 계산 엔진 기준이 달라 기준과 겹쳐 그리지 않았습니다. 같은 현재 기준으로 다시 계산해 비교할 수 있습니다.',
} as const

const STAGES: Record<string, string> = {
  PREPARING_DATA: '자료 준비',
  COMPUTING: '분석 계산',
  SAVING: '결과 저장',
}

/** 칩에 붙는 짧은 상태 말과, 도움말·보조 기술에 주는 긴 문장. READY 이고 불일치가 없으면 둘 다 없다. */
function describe(state: CompareTargetState): { short: string | null; long: string | null } {
  const detail = state.detail
  const progress = detail?.progress
  switch (state.targetState) {
    case 'REQUESTED':
      return { short: '요청 중', long: '비교 결과를 요청하고 있습니다.' }
    case 'RUNNING': {
      if (detail?.status === 'QUEUED') {
        return {
          short: '대기 중',
          long:
            '비교 분석이 차례를 기다리고 있습니다.' +
            (detail.queueWaitSeconds !== null
              ? ' 지금까지 ' + detail.queueWaitSeconds + '초 기다렸습니다.'
              : ''),
        }
      }
      const ratio =
        progress?.completedTradingDays != null && progress.totalTradingDays
          ? Math.round((progress.completedTradingDays / progress.totalTradingDays) * 100)
          : null
      return {
        short: ratio === null ? '계산 중' : '계산 중 ' + ratio + '%',
        long:
          (STAGES[progress?.stage ?? ''] ?? '분석 진행') +
          (progress?.completedTradingDays != null && progress.totalTradingDays
            ? ': ' +
              progress.completedTradingDays +
              '/' +
              progress.totalTradingDays +
              '거래일 (' +
              ratio +
              '%)'
            : ''),
      }
    }
    case 'EXECUTION_LIMIT':
      return {
        short: '한도',
        long:
          '실행 한도에 걸렸습니다. ' +
          (state.retryAfterSeconds !== null
            ? state.retryAfterSeconds + '초 뒤 다시 요청할 수 있습니다.'
            : (state.message ?? '')),
      }
    case 'FAILED':
      return { short: '실패', long: state.message ?? '비교 분석을 완료하지 못했습니다.' }
    case 'EXPIRED':
      return {
        short: '만료',
        long: '비교 대상 결과가 더 이상 남아 있지 않습니다. 기준 결과는 계속 볼 수 있습니다.',
      }
    case 'READY':
      return state.mismatch
        ? { short: '조건 다름', long: MISMATCH_MESSAGES[state.mismatch] }
        : { short: null, long: null }
  }
}

/**
 * 비교 대상 하나의 칩(FR-004, `S15P21E205-419`). '비교 대상 고르기' 단추 왼쪽에 대상 수만큼 늘어선다.
 *
 * <p>이름은 한 줄로 자른다 — 폭 상한을 두고 넘치면 말줄임이다. 전체 이름과 상태 문장은 도움말(`title`)과 보조 기술용 글에
 * 그대로 있다. 겹쳐 그려진 대상은 이름만, 그 밖의 상태는 이름 옆에 짧은 말(계산 중 42% · 실패 · 조건 다름)을 붙인다.
 *
 * <p>다시 시도할 수 있는 상태(실패·한도)면 칩 본체가 다시 시도 단추이고, × 는 늘 해제다. 화면이 조용히 재시도하지 않는다(원칙 IX).
 * `name`은 부른 쪽이 정한다 — 이 컴포넌트는 이름을 짓지 않는다.
 */
export function CompareTargetChip({
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
  const { short, long } = describe(state)
  const retryable =
    state.retryable && (state.targetState === 'FAILED' || state.targetState === 'EXECUTION_LIMIT')
  const retryLabel = state.targetState === 'EXECUTION_LIMIT' ? '다시 요청' : '다시 시도'
  const body = (
    <>
      <span className={styles.chipName}>{name}</span>
      {short ? <span className={styles.chipState}>{short}</span> : null}
    </>
  )
  return (
    <span
      className={styles.chip}
      data-state={state.targetState}
      aria-label={'비교 대상 ' + name}
      title={long ? name + ' — ' + long : name}
    >
      {retryable ? (
        <button
          type="button"
          className={styles.chipBody}
          aria-label={retryLabel}
          title={long ?? undefined}
          onClick={onRetry}
        >
          {body}
        </button>
      ) : (
        <span className={styles.chipBody}>{body}</span>
      )}
      {/*
        상태 문장은 화면에서는 도움말이고 보조 기술에는 글이다. 살아 있는 영역(aria-live)으로 두지 않는다 —
        대화상자가 열려 나머지를 숨길 때 살아 있는 영역만 남아 대상 고르기 단추의 이름 계산이 어긋난다(확인: 2026-09-23).
      */}
      {long ? <span className={styles.visuallyHidden}>{long}</span> : null}
      <button
        type="button"
        className={styles.chipRemove}
        aria-label="비교 해제"
        title={name + ' 비교 해제'}
        onClick={onRemove}
      >
        <X size={13} strokeWidth={2} aria-hidden="true" />
      </button>
    </span>
  )
}
