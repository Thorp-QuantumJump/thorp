import {
  confirmAcceptLabel,
  confirmRejectLabel,
  fillsMissingConfirmMessage,
  fullPeriodConfirmMessage,
} from './fixedCopy'

/**
 * 전체 기간 재계산 확인 (T072 · T092).
 *
 * 계약 §2의 200 응답을 받은 자리다. **확인 전에는 계산이 만들어지지 않는다** — 이 컴포넌트는
 * 사용자가 누를 때만 `onConfirm`을 부르고, 거절하면 자리에 '분석 기간 밖'이 그대로 남는다.
 *
 * 재계산 사유가 둘이라 문구가 다르다. 분석 기간이 사후 구간을 덮지 않는 경우와, 체결 기록이 없는
 * 옛 실행(T092)이다. 값을 추정해 채우지 않고 다시 계산한다.
 */
export interface FullPeriodConfirmProps {
  outcome: string
  requiredRange?: { start: string; end: string }
  affected?: { publicToken: string; period?: { start: string; end: string } }[]
  onConfirm: () => void
  onReject: () => void
}

export function FullPeriodConfirm({
  outcome,
  requiredRange,
  affected,
  onConfirm,
  onReject,
}: FullPeriodConfirmProps) {
  const fillsMissing = outcome === 'FILLS_MISSING_CONFIRM_REQUIRED'
  return (
    <div role="dialog" aria-label="전체 기간 재계산 확인" data-confirm-outcome={outcome}>
      <p>{fillsMissing ? fillsMissingConfirmMessage : fullPeriodConfirmMessage}</p>
      {requiredRange && (
        <p>{`다시 계산할 기간 ${requiredRange.start} ~ ${requiredRange.end}`}</p>
      )}
      {affected && affected.length > 0 && (
        <ul>
          {affected.map((run) => (
            <li key={run.publicToken}>
              {run.period ? `${run.period.start} ~ ${run.period.end}` : '기간을 확인할 수 없습니다.'}
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={onConfirm}>
        {confirmAcceptLabel}
      </button>
      <button type="button" onClick={onReject}>
        {confirmRejectLabel}
      </button>
    </div>
  )
}
