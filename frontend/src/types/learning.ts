/**
 * spec 013 배우기 공개 조회 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-public-api.md`이며 필드는 그것과 1:1이다.
 * 지금은 진단 화면 도움말(§3)이 쓰는 것만 두었다. 글 목록·글 본문 타입은 013 구현이 이 파일에 더한다
 * (013 data-model §10).
 */

/** `GET /learning/terms/{termId}` 응답 본문. 설명 문장은 화면이 짓거나 줄이지 않고 그대로 보인다. */
export type LearningTermEntry = {
  termId: string
  label: string
  sentence: string
  /** 도움말의 '해당 주제 글로 이동' 대상 */
  articleId: string
  articleTitle: string
}

/**
 * 도움말에서 글로 갔다가 돌아올 자리. navigation state `learningReturn`에 싣는다
 * (013 term-help-ui §3, research R11).
 */
export type LearningReturnState = {
  path: string
  label: string
}
