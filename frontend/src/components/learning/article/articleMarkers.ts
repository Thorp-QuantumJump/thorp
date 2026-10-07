/**
 * 글 화면의 기록 훅들이 함께 쓰는 표지와 단위(013 FR-020·FR-021, research R32·R33).
 *
 * <p>완료 판정(`useArticleCompletion`)과 읽은 위치(`useReadingPosition`)가 같은 본문 끝 표지를 찾고, 기록
 * (`useLearnerArticle`)과 두 훅이 같은 단위로 처음부터 한다. 한곳에 두어 셋이 어긋나지 않게 한다.
 */

/** 본문 마지막 요소 바로 뒤의 표지(`ArticleBody`). 예시 블록 앞이다. */
const ARTICLE_END_SELECTOR = '[data-article-end]'

/** 글 안의 본문 끝 표지. 없으면 `null`이다. */
export function articleEndOf(root: HTMLElement | null): Element | null {
  return root?.querySelector(ARTICLE_END_SELECTOR) ?? null
}

/** 본문 끝 표지를 감싼 본문 요소. 읽은 비율은 이 요소의 높이로 잰다. */
export function articleBodyOf(root: HTMLElement | null): HTMLElement | null {
  return articleEndOf(root)?.parentElement ?? null
}

/**
 * 글 하나를 확인된 로그인 상태 하나로 읽는 단위. 이 값이 바뀌면 기록·머문 시간·읽은 비율을 처음부터 한다.
 * `sessionKey`는 확인된 로그인 상태(로그인했으면 사용자까지)이고, 확인 중(`checking`)과 `error`는 바뀐 것으로
 * 보지 않는다(R33).
 */
export function articleSessionKey(articleId: string, sessionKey: string): string {
  return `${articleId}|${sessionKey}`
}
