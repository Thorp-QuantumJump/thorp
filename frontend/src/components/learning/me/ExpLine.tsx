import type { LevelView } from '@/types/learningMe'

/**
 * '경험치 360 · 다음 레벨까지 240'(013 design-notes §3.3·§3.4, 목업 `levelCard`·`doneCard`). 레벨 카드와 완료 카드의
 * 다음 목표가 같은 글자를 쓴다. 놓이는 자리의 글자 모양은 부르는 쪽이 `className`으로 정한다.
 */
export function ExpLine({
  level,
  className,
}: {
  level: Pick<LevelView, 'exp' | 'remainingToNext'>
  className?: string
}) {
  return (
    <p className={className}>
      경험치 <b>{level.exp.toLocaleString('ko-KR')}</b> · 다음 레벨까지{' '}
      <b>{level.remainingToNext.toLocaleString('ko-KR')}</b>
    </p>
  )
}
