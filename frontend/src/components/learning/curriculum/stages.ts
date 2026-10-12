import type { Curriculum, StageView } from '@/types/learning'

/** 단계에 대한 사실. 문장 틀(`curriculumCopy`)과 화면 부품이 함께 쓴다. */

/** 공개 경계로 세는 단계. 글이 0편인 공개 단계(`REPREPARING`)도 공개된 단계다(계약 §1). */
export function isReleased(stage: Pick<StageView, 'status'>): boolean {
  return stage.status !== 'PREPARING'
}

/**
 * 첫 단계를 공개했는가. 나의 학습(FR-020~FR-030)은 이때 켠다(FR-031, research R33). 배우기 목록의 기록 자리, '나의
 * 학습' 화면, 회원정보의 '학습 기록' 구역(FR-025)이 이 값으로 갈린다. 공개 전 목록(`preReleaseArticles`)은 공개한 단계가
 * 없을 때만 배열이다(계약 §1).
 */
export function isFirstStageReleased(
  curriculum: Pick<Curriculum, 'preReleaseArticles' | 'releasedStageCount'>,
): boolean {
  return curriculum.preReleaseArticles === null && curriculum.releasedStageCount > 0
}

/** 단계의 공개된 글을 모두 읽는 데 드는 예상 시간(분). */
export function stageMinutes(stage: Pick<StageView, 'articles'>): number {
  return stage.articles.reduce((sum, article) => sum + article.readingMinutes, 0)
}
