import type { PublicResultDetail } from '@/types/result'

export const SUMMARY_NOT_CALCULATED = '취소 시점까지의 지표는 계산되지 않았습니다.'

/**
 * 결과 의미를 바꾸는 상태의 고정 문장 틀(FR-013, data-model §3-4).
 *
 * <p>이 파일은 SC-004의 검수 대상이다. 컴포넌트는 문장을 만들지 않고 이 틀의 값 자리만 채운다.
 */
export const STATUS_SENTENCE_TEMPLATES = {
  partialWithDate: '{completedThrough}까지 계산된 부분 결과입니다.',
  partialWithoutDate: '완료 범위를 확인할 수 있는 데까지만 계산된 부분 결과입니다.',
  cancelledWhileRunContinued: SUMMARY_NOT_CALCULATED,
  cancelUnanswered:
    '취소 시점까지의 지표는 계산되지 않았습니다. 취소 요청에 대한 실행 상태를 확인하지 못했습니다.',
  cancelledBeforeStart:
    '취소 시점까지의 지표는 계산되지 않았습니다. 계산을 시작하기 전에 취소되어 완료된 거래일이 없습니다.',
  summaryUnavailable: '핵심 지표는 계산되지 않았습니다.',
  limitations: '자료 부족·계산 제한 기록이 {count}건 있습니다.',
  limitationKinds: '종류: {kinds}.',
  failure: '실행이 끝나지 못했습니다({code}): {message}',
  failureWithoutDetail: '실행이 끝나지 못했습니다.',
} as const

function fill(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (placeholder, key: string) => values[key] ?? placeholder)
}

function summaryUnavailableSentence(detail: PublicResultDetail): string | null {
  switch (detail.summaryUnavailable) {
    case null:
      return null
    case 'CANCELLED_WHILE_RUN_CONTINUED':
      return STATUS_SENTENCE_TEMPLATES.cancelledWhileRunContinued
    case 'CANCEL_UNANSWERED':
      return STATUS_SENTENCE_TEMPLATES.cancelUnanswered
    case 'CANCELLED_BEFORE_START':
      return STATUS_SENTENCE_TEMPLATES.cancelledBeforeStart
    default:
      return STATUS_SENTENCE_TEMPLATES.summaryUnavailable
  }
}

/**
 * 결과 의미를 바꾸는 상태를 한 단락으로 만든다. 알릴 상태가 없으면 빈 문자열이다.
 *
 * <p>여러 상태가 함께 있으면 문장을 이어 쓰되 경고 목록으로 늘리지 않는다. 서버가 주지 않은 사유나
 * 완료 범위를 지어내지 않는다.
 */
export function selectStatusSentence(
  detail: PublicResultDetail,
  completedThrough: string | null,
): string {
  const parts: string[] = []

  if (detail.partial && detail.summaryUnavailable !== 'CANCELLED_BEFORE_START') {
    parts.push(
      completedThrough
        ? fill(STATUS_SENTENCE_TEMPLATES.partialWithDate, { completedThrough })
        : STATUS_SENTENCE_TEMPLATES.partialWithoutDate,
    )
  }

  const unavailable = summaryUnavailableSentence(detail)
  if (unavailable) parts.push(unavailable)

  const kinds = detail.exceptionKinds ?? []
  const exceptionCount = detail.summary?.exceptionCount ?? 0
  if (exceptionCount > 0 || kinds.length > 0) {
    parts.push(
      fill(STATUS_SENTENCE_TEMPLATES.limitations, {
        count: String(exceptionCount || kinds.length),
      }),
    )
    if (kinds.length > 0) {
      parts.push(fill(STATUS_SENTENCE_TEMPLATES.limitationKinds, { kinds: kinds.join(', ') }))
    }
  }

  if (detail.status === 'FAILED') {
    parts.push(
      detail.failure
        ? fill(STATUS_SENTENCE_TEMPLATES.failure, {
            code: detail.failure.code,
            message: detail.failure.message,
          })
        : STATUS_SENTENCE_TEMPLATES.failureWithoutDetail,
    )
  }

  return parts.join(' ')
}
