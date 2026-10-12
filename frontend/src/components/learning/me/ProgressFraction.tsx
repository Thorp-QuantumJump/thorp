import type { BadgeView } from '@/types/learningMe'
import { progressSpoken } from './meCopy'

/**
 * 배지 진행 'n/m'(013 design-notes §4, 목업 `bcard`). 화면에는 '3/5'로 보이고 화면 낭독기는 '5편 중 3편'으로
 * 읽는다. `lead`가 있으면 앞에 상태 낱말을 붙인다. 보이는 글자는 '아직 받지 않음 · 3/5', 읽는 글자는 '아직 받지 않음,
 * 5편 중 3편'이다. 진행이 없는 배지는 아무것도 그리지 않는다.
 */
export function ProgressFraction({
  badge,
  lead,
}: {
  badge: Pick<BadgeView, 'family' | 'progress'>
  lead?: string
}) {
  if (!badge.progress) return null
  const { current, target } = badge.progress
  return (
    <>
      <span aria-hidden="true">{`${lead ? `${lead} · ` : ''}${current}/${target}`}</span>
      <span className="sr-only">{`${lead ? `${lead}, ` : ''}${progressSpoken(badge)}`}</span>
    </>
  )
}
