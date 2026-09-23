import type { RegimeLinkView } from '@/types/regimeLink'
import { resultSlotKind } from './resultSlot'
import {
  dataInsufficientReason,
  dataInsufficientTitle,
  failureTitle,
  notRetryableNote,
  outOfPeriodNote,
  outOfPeriodTitle,
  pendingTitle,
  postIncompleteReason,
  postIncompleteTitle,
  retryLabel,
  runningTitle,
} from './fixedCopy'

/**
 * 사후 구간 결과 자리의 상태 (T071 · US4).
 *
 * **'자료 부족'과 '실패'를 섞지 않는다.** 앞의 것은 계산 작업을 만들지 않은 판정이라 '다시 시도'가
 * 없고, 뒤의 것만 `retryable`을 본다(research R13). 실패 문구는 사용자의 조건을 원인으로 말하지
 * 않는다 — 서버가 보낸 한 문장을 그대로 보인다.
 */
interface ResultSlotStateProps {
  link: RegimeLinkView
  onRetry?: (linkId: string) => void
}

/** `READY`면 아무것도 그리지 않는다 — 그 자리는 결과가 채운다. */
export function ResultSlotState({ link, onRetry }: ResultSlotStateProps) {
  const kind = resultSlotKind(link)
  if (kind === 'READY') return null

  if (kind === 'DATA_INSUFFICIENT') {
    const reason = link.availability.reason
    return (
      <div role="status" data-slot-state="DATA_INSUFFICIENT">
        <p>{dataInsufficientTitle}</p>
        {reason && dataInsufficientReason[reason] && <p>{dataInsufficientReason[reason]}</p>}
      </div>
    )
  }

  if (kind === 'POST_INCOMPLETE') {
    const reason = link.availability.reason
    return (
      <div role="status" data-slot-state="POST_INCOMPLETE">
        <p>{postIncompleteTitle}</p>
        <p>{`${link.postWindow.completedTradingDays}거래일만 자료가 있습니다.`}</p>
        {reason && postIncompleteReason[reason] && <p>{postIncompleteReason[reason]}</p>}
      </div>
    )
  }

  if (kind === 'OUT_OF_PERIOD') {
    return (
      <div role="status" data-slot-state="OUT_OF_PERIOD">
        <p>{outOfPeriodTitle}</p>
        <p>{outOfPeriodNote}</p>
      </div>
    )
  }

  if (kind === 'FAILED') {
    const retryable = link.failure?.retryable === true
    return (
      <div role="status" data-slot-state="FAILED">
        <p>{failureTitle}</p>
        {link.failure && <p>{link.failure.message}</p>}
        {retryable ? (
          <button type="button" onClick={() => onRetry?.(link.linkId)}>
            {retryLabel}
          </button>
        ) : (
          <p>{notRetryableNote}</p>
        )}
      </div>
    )
  }

  const progress = link.runProgress
  return (
    <div role="status" data-slot-state={kind}>
      <p>{kind === 'PENDING' ? pendingTitle : runningTitle}</p>
      {progress && (
        <dl>
          <dt>단계</dt>
          <dd>{progress.status}</dd>
          {progress.progress != null && (
            <>
              <dt>진행</dt>
              <dd>{progress.progress}</dd>
            </>
          )}
          {progress.queueWaitSeconds != null && (
            <>
              <dt>기다린 시간(초)</dt>
              <dd>{progress.queueWaitSeconds}</dd>
            </>
          )}
        </dl>
      )}
    </div>
  )
}

/**
 * 결과는 있지만 구간이 온전하지 않은 경우의 덧말.
 *
 * 63거래일이 다 차지 않았으면 완료된 것처럼 요약하지 않고 길이를 밝히고, 구간 안 상장폐지로
 * 줄어든 구간은 폐지일과 함께 부분 구간임을 밝힌다(Clarifications 09-18 Q2).
 */
export function PostWindowCompleteness({ link }: { link: RegimeLinkView }) {
  const days = link.postWindow.completedTradingDays
  const partial = link.availability.partial === true
  if (partial) {
    return (
      <p data-slot-note="PARTIAL">
        {`${days}거래일(상장폐지로 부분 구간)`}
        {link.availability.delistedOn ? ` · 폐지일 ${link.availability.delistedOn}` : ''}
      </p>
    )
  }
  if (!link.postWindow.complete63) {
    return <p data-slot-note="INCOMPLETE">{`${days}거래일 완성(미완성)`}</p>
  }
  return null
}
