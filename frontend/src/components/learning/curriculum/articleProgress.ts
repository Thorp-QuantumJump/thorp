import type { StageView } from '@/types/learning'
import type { LearnerArticleEntry, LearnerStageEntry } from '@/types/learningMe'
import type { ArticleStampState } from '../me/ArticleStamp'

/**
 * 커리큘럼 보기에 넘기는 글 하나의 나의 학습 상태(013 FR-020·FR-023).
 *
 * <p>값은 '나의 학습' 조회(`progressMapOf`)가 채운다. 부품은 값을 받으면 그리고, 받지 않으면 상태 글자·
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

/** 단계 하나의 완료 수(계약 §1 `stages[]`). 공개된 단계만 둘 다 있다. */
export type StageCount = { completedCount: number; publishedCount: number; completed: boolean }

/** 글 id로 찾는 상태표. 없는 글은 상태를 모르는 글이다. */
export type ArticleProgressMap = ReadonlyMap<string, ArticleProgress>

/** '나의 학습' 응답의 글별 기록(계약 §1 `articles`)으로 상태표를 만든다. */
export function progressMapOf(articles: readonly LearnerArticleEntry[]): ArticleProgressMap {
  const map = new Map<string, ArticleProgress>()
  for (const entry of articles) {
    const masteredTermCount = entry.masteredTermCount
    if (entry.state === 'COMPLETED') map.set(entry.articleId, { state: 'done', masteredTermCount })
    else if (entry.state === 'READING') map.set(entry.articleId, { state: 'reading', percent: entry.percent, masteredTermCount })
    else map.set(entry.articleId, { state: 'unread', masteredTermCount })
  }
  return map
}

/**
 * '나의 학습' 응답의 단계별 진행(계약 §1 `stages`)에서 공개된 단계의 완료 수만 모은다. 준비 중 단계와 글이 0편인
 * 단계는 넣지 않는다.
 */
export function stageCountsOf(stages: readonly LearnerStageEntry[]): ReadonlyMap<number, StageCount> {
  const map = new Map<number, StageCount>()
  for (const stage of stages) {
    if (stage.status !== 'OPEN' || stage.completedCount === null || stage.publishedCount === null) continue
    if (stage.publishedCount === 0) continue
    map.set(stage.number, {
      completedCount: stage.completedCount,
      publishedCount: stage.publishedCount,
      completed: stage.completed,
    })
  }
  return map
}

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

/**
 * 단계 진행 글자(design-notes §3.1·§3.3). 다 읽었으면 '완주 · 4편', 아니면 '4편 중 3편 읽음'이다. 단계 띠·목록 보기의
 * 단계 구분 줄·'나의 학습'의 정거장·완료 카드가 같은 글자를 쓴다.
 */
export function stageProgressLabel({ completed, completedCount, publishedCount }: StageCount): string {
  return completed ? `완주 · ${publishedCount}편` : `${publishedCount}편 중 ${completedCount}편 읽음`
}

/** 글 도장의 모양. 상태를 모르면 중립 모양이다. */
export function stampState(progress: ArticleProgress | undefined): ArticleStampState {
  if (!progress) return 'neutral'
  return progress.state === 'reading' ? 'reading' : progress.state
}

/**
 * 길 한 구간의 모양(design-notes §3.1). 읽은 글로 가는 구간은 초록 실선(`read`), 아직 읽지 않은 글로 가는 구간은
 * 회색 실선(`unread`), 상태를 모르면 짙은 중립 길(`neutral`), 준비 중 단계로 가는 구간은 점선(`preparing`)이다.
 */
export type RoadKind = 'read' | 'unread' | 'neutral' | 'preparing'

/** 길이 지나는 점. 구간의 모양은 도착하는 점이 정하고, 길의 출발점(`start`)은 어느 구간의 도착점도 아니다. */
export type RoadPointKind = RoadKind | 'start'

export function roadKind(progress: ArticleProgress | undefined): RoadKind {
  if (!progress) return 'neutral'
  return progress.state === 'done' ? 'read' : 'unread'
}

/**
 * 공개된 단계 하나의 표식과 그 띠로 들어오는 길. 단계의 글 상태를 모두 알 때만 상태 모양이다. 다 읽었으면 체크,
 * 아니면 실선 칸이다. 하나라도 모르면 중립 원판과 중립 길이다.
 */
export function stageMarker(
  stage: Pick<StageView, 'articles'>,
  progress: ArticleProgressMap | undefined,
): { marker: Extract<ArticleStampState, 'neutral' | 'unread' | 'done'>; road: RoadKind } {
  const states = stage.articles.map((article) => progress?.get(article.articleId))
  if (!progress || !states.every(Boolean)) return { marker: 'neutral', road: 'neutral' }
  return { marker: states.every((state) => state?.state === 'done') ? 'done' : 'unread', road: 'unread' }
}
