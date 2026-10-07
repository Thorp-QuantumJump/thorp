import type { StageView } from '@/types/learning'

/** 단계에 대한 사실. 문장 틀(`curriculumCopy`)과 화면 부품이 함께 쓴다. */

/** 공개 경계로 세는 단계. 글이 0편인 공개 단계(`REPREPARING`)도 공개된 단계다(계약 §1). */
export function isReleased(stage: Pick<StageView, 'status'>): boolean {
  return stage.status !== 'PREPARING'
}

/** 단계의 공개된 글을 모두 읽는 데 드는 예상 시간(분). */
export function stageMinutes(stage: Pick<StageView, 'articles'>): number {
  return stage.articles.reduce((sum, article) => sum + article.readingMinutes, 0)
}
