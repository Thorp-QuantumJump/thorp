import type { RegimeLinkView } from '@/types/regimeLink'

export type ResultSlotKind =
  | 'DATA_INSUFFICIENT'
  | 'POST_INCOMPLETE'
  | 'OUT_OF_PERIOD'
  | 'FAILED'
  | 'PENDING'
  | 'RUNNING'
  | 'READY'

/** 자리에 무엇을 그릴지 하나로 정한다. 여러 컴포넌트가 각자 판정하면 표시가 갈라진다. */
export function resultSlotKind(link: RegimeLinkView): ResultSlotKind {
  if (link.availability.state === 'DATA_INSUFFICIENT') return 'DATA_INSUFFICIENT'
  if (link.availability.state === 'POST_INCOMPLETE') return 'POST_INCOMPLETE'
  if (link.availability.state === 'OUT_OF_PERIOD') return 'OUT_OF_PERIOD'
  if (link.status === 'FAILED') return 'FAILED'
  if (link.status === 'PENDING_RUN' || link.status === 'QUEUED') return 'PENDING'
  if (link.status === 'RUNNING') return 'RUNNING'
  return 'READY'
}

