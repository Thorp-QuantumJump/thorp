import type { ArticleStampState } from '../me/ArticleStamp'

/**
 * 커리큘럼 보기·목록 보기에 넘기는 글 하나의 나의 학습 상태(013 FR-020·FR-023).
 *
 * <p>값은 '나의 학습' 조회(B13 T192)가 채운다. 이 묶음(B11)의 부품은 값을 받으면 그리고, 받지 않으면 상태 글자·
 * 익힌 용어 수를 비우고 중립 모양으로 그린다. 비로그인 사용자와 기록을 불러오지 못한 때가 그 경우이며, 어느 경우도
 * '안 읽음'이나 0편으로 채우지 않는다(FR-020).
 */
export type ArticleProgress = (
  | { state: 'done' }
  /** `percent`는 가장 멀리 읽은 비율(1~99) */
  | { state: 'reading'; percent: number }
  | { state: 'unread' }
) & {
  /** 그 글의 익힌 용어 수. 용어가 없는 글은 보이지 않는다 */
  masteredTermCount: number
}

/** 글 id로 찾는 상태표. 없는 글은 상태를 모르는 글이다. */
export type ArticleProgressMap = ReadonlyMap<string, ArticleProgress>

/** 상태 글자(design-notes §4). 색과 모양은 보조이고 이 글자가 원본이다. */
export function progressLabel(progress: ArticleProgress): string {
  switch (progress.state) {
    case 'done':
      return '완료'
    case 'reading':
      return `${progress.percent}% 읽는 중`
    case 'unread':
      return '안 읽음'
  }
}

/** 글 도장의 모양. 상태를 모르면 중립 모양이다. */
export function stampState(progress: ArticleProgress | undefined): ArticleStampState {
  if (!progress) return 'neutral'
  return progress.state === 'reading' ? 'reading' : progress.state
}

/**
 * 커리큘럼 보기 길의 구간 종류. 읽은 글로 가는 구간은 초록 실선(`g`), 아직 읽지 않은 글로 가는 구간은 회색 실선
 * (`s`), 상태를 모르면 짙은 중립 길(`n`), 준비 중 단계로 가는 구간은 점선(`d`)이다(design-notes §3.1).
 */
export type RoadKind = 'g' | 's' | 'n' | 'd'

export function roadKind(progress: ArticleProgress | undefined): RoadKind {
  if (!progress) return 'n'
  return progress.state === 'done' ? 'g' : 's'
}
