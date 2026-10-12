import type { ContinueReading, LearningResumeState } from '@/types/learningMe'

/**
 * '이어서 읽기'가 글을 열 때 싣는 navigation state. 읽는 중 글(`RESUME`)이면 `learningResume`을 실어 글 화면이 기록된
 * 위치로 옮기게 한다(data-model §15). 받은 state(도움말에서 온 돌아갈 자리)는 잃지 않게 함께 싣는다.
 */
export function continueLinkState(
  kind: ContinueReading['kind'],
  carried: unknown,
): object | undefined {
  const base = typeof carried === 'object' && carried !== null ? carried : undefined
  if (kind !== 'RESUME') return base
  const resume: LearningResumeState = { learningResume: true }
  return { ...base, ...resume }
}

/** 글 번호(`2-3`)의 단계. 읽지 못하면 `undefined`다. */
export function stageOfNumber(number: string | null): number | undefined {
  const stage = Number(number?.split('-')[0])
  return Number.isInteger(stage) && stage > 0 ? stage : undefined
}
