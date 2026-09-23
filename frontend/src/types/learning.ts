/**
 * spec 013 배우기 공개 조회 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-public-api.md`이며 필드는 그것과 1:1이다
 * (013 data-model §10).
 *
 * **글 본문·용어 문장을 이 코드에 상수로 두지 않는다(013 FR-015).** 화면은 서버가 보낸 판을 그대로
 * 그린다. 글의 제목·순서·용어 구성이 바뀌어도 프런트엔드 배포가 필요 없어야 한다.
 */

/** `GET /learning/articles` 목록의 한 항목. */
export type LearningArticleSummary = {
  articleId: string
  title: string
  question: string
  /** 읽는 순서 제안. 서버가 이 순서로 보내며 화면은 다시 정렬하지 않는다 */
  readingOrder: number
}

/** `GET /learning/articles` 응답 본문. 게시된 글이 없으면 빈 배열이다(오류가 아니다). */
export type LearningArticleList = {
  articles: LearningArticleSummary[]
}

/**
 * 글 본문의 블록. `type`으로 가른다.
 *
 * `term` 블록의 `label`·`sentence`는 서버가 같은 판의 용어 설명에서 채워 보낸다. 도움말 조회가
 * 돌려주는 문장과 같은 값이다(013 SC-003).
 */
export type ArticleBlock =
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'term'; termId: string; label: string; sentence: string }
  /** 화면이 '가상의 계산 예' 표시를 붙인다(013 FR-005) */
  | { type: 'hypothetical'; text: string }

export type LearningExampleUnavailableReason = 'SECTOR_NOT_IN_MAP' | 'SECTOR_MAP_NOT_READY'

/** 예시의 섹터 한 줄. */
export type LearningExampleSector = {
  code: string
  /** 열린 대응표의 섹터 이름. 대응표에 없는 코드는 null이다 */
  name: string | null
  /** 0.1%p 정수, 1000이 100%다. 002 계약과 같은 단위라 변환하지 않고 넘긴다 */
  weightTenths: number
}

/** 글의 예시와 조회 시점의 대응표 판정. */
export type LearningExample = {
  /** 열린 대응표에 예시 섹터가 모두 있는가 */
  available: boolean
  sectors: LearningExampleSector[]
  /** 대응표에 없는 코드. `SECTOR_MAP_NOT_READY`는 판정하지 못했으므로 빈 배열이다 */
  missingSectorCodes: string[]
  /** `available=false`일 때만 값이 있다. 화면은 이 값으로 안내 문장을 가른다 */
  unavailableReason: LearningExampleUnavailableReason | null
}

/** 글의 출처. `url`은 없을 수 있고, 있으면 `http`·`https`만 온다. */
export type LearningSource = {
  label: string
  url: string | null
}

/** `GET /learning/articles/{articleId}` 응답 본문. 공개 중인 판의 내용이다. */
export type LearningArticle = {
  articleId: string
  title: string
  question: string
  readingOrder: number
  blocks: ArticleBlock[]
  example: LearningExample
  sources: LearningSource[]
  /** ISO 8601 시각 */
  publishedAt: string
}

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

/**
 * 배우기 공개 조회가 내보내는 서버 오류 신호 이름이다.
 *
 * 두 404를 한 이름으로 합치지 않는다. 글은 '지금은 볼 수 없는 글', 용어는 '설명 준비 중'으로
 * 화면 문장이 다르다(013 research R10). 이 union을 공용 `ServiceApiErrorCode`에 잇는 쪽은 `./api`다.
 */
export type LearningApiErrorCode = 'LEARNING_ARTICLE_UNAVAILABLE' | 'LEARNING_TERM_NOT_READY'
