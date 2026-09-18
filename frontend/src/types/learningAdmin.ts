/**
 * spec 013 배우기 **관리** 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-admin-api.md`이며 필드는 그것과 1:1이다
 * (013 data-model §10). 공개 조회의 타입은 `./learning`에 있고 이 파일은 그것을 대신하지 않는다 —
 * **초안과 공개 판은 모양이 다르다.** 초안의 `term` 블록은 `termId`만 담고 문장은 `terms`에 있다
 * (data-model §4). 둘을 한 타입으로 합치면 화면이 아직 없는 문장을 있는 것처럼 다루게 된다.
 *
 * **글 본문·용어 문장을 이 코드에 상수로 두지 않는다(013 FR-015).** 운영자가 서버에 넣은 값만 그린다.
 */

/** 글의 게시 상태. `PUBLISHED`일 때만 게시 취소가 있다(계약 §7). */
export type AdminArticleStatus = 'DRAFT' | 'PUBLISHED'

/** 게시·게시 취소 기록 한 줄(FR-018). `actorUserId`는 사용자 식별자이며 이메일·닉네임이 아니다. */
export type AdminPublicationRecord = {
  action: 'PUBLISH' | 'UNPUBLISH'
  /** 게시 취소가 아무것도 바꾸지 않았으면 `null`이다 */
  revisionNo: number | null
  actorUserId: number
  /** ISO 8601 시각 */
  occurredAt: string
}

/** `GET /admin/learning/articles` 목록의 한 항목(계약 §2). */
export type AdminLearningArticleSummary = {
  articleId: string
  title: string
  readingOrder: number
  status: AdminArticleStatus
  draftVersion: number
  /** 공개 중인 판 이후 초안이 저장됐는가. 게시를 잊는 일을 막는 표시다 */
  hasUnpublishedChanges: boolean
  /**
   * 공개 중인 판의 예시 섹터가 지금 열린 대응표에 없다(R8).
   *
   * **`null`을 `false`로 접지 않는다.** 대응표를 읽지 못해 판정하지 못한 것과 판정해서 괜찮은 것은
   * 다른 사실이다. 판정할 수 없는 것을 통과로 세지 않는다(constitution 원칙 VI).
   */
  needsUpdate: boolean | null
  /** 기록이 없으면 `null`이다 */
  lastPublication: AdminPublicationRecord | null
}

/** 초안 본문의 블록. 공개 판과 달리 `term`은 `termId`만 담는다(계약 §4). */
export type AdminDraftBlock =
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'term'; termId: string }
  | { type: 'hypothetical'; text: string }

/** 초안 예시의 섹터 한 줄. `weightTenths`는 0.1%p 정수이고 1000이 100%다(002 계약과 같은 단위). */
export type AdminDraftExampleSector = {
  code: string
  weightTenths: number
}

/** 초안의 예시. 초안은 게시 전 검사를 받지 않으므로 통째로 없을 수 있다(계약 §5). */
export type AdminDraftExample = {
  sectors: AdminDraftExampleSector[]
}

export type AdminDraftSource = {
  label: string
  url: string | null
}

/** 이 글이 소유한 용어 설명 한 줄. `termId`는 만든 뒤 바꾸지 않는다(research R4). */
export type AdminDraftTerm = {
  termId: string
  label: string
  sentence: string
}

/** 초안 한 벌. 저장 요청은 이 값을 통째로 보낸다 — 빠진 용어는 초안에서 빠진다(계약 §5). */
export type AdminLearningDraft = {
  title: string
  question: string
  readingOrder: number
  blocks: AdminDraftBlock[]
  example: AdminDraftExample | null
  sources: AdminDraftSource[]
  terms: AdminDraftTerm[]
}

/** `GET`·`PUT`·`POST`(만들기) 응답 본문(계약 §3·§4·§5). */
export type AdminLearningArticle = {
  articleId: string
  status: AdminArticleStatus
  /** 저장할 때마다 1 오른다. 저장·게시 요청이 마지막으로 읽은 값을 싣는다(R12) */
  draftVersion: number
  draft: AdminLearningDraft
  /** 공개 중인 판 번호. 초안이면 `null`이다 */
  publishedRevisionNo: number | null
}

/** `POST /admin/learning/articles` 요청 본문(계약 §3). */
export type CreateArticleRequest = {
  articleId: string
  title: string
  readingOrder: number
}

/** `PUT /admin/learning/articles/{articleId}` 요청 본문(계약 §5). 초안을 통째로 바꾼다. */
export type SaveDraftRequest = AdminLearningDraft & {
  expectedDraftVersion: number
}

/** 게시 전 검사에 걸린 위치(계약 §6). `part`에 따라 따라오는 필드가 다르다. */
export type PublishCheckLocation = {
  part: 'title' | 'question' | 'example' | 'block' | 'term'
  /** `part === 'block'`일 때의 순번(0부터). 본문 전체를 가리키면 없다 */
  index?: number
  /** `part === 'term'`일 때의 용어 식별자 */
  termId?: string
}

/**
 * 걸린 검사 하나(계약 §6).
 *
 * `check`를 닫힌 union으로 두지 않는다. 검사 목록은 버전이 붙은 서버 자원 파일이 소유하므로(R7)
 * 서버가 검사를 늘려도 화면이 그 항목을 버리면 안 된다. 화면은 `message`를 **그대로** 보이고
 * 문장을 새로 짓지 않는다.
 */
export type PublishCheckFailure = {
  check: string
  location: PublishCheckLocation
  /** 걸린 표현·숫자. 해당 검사일 때만 온다 */
  matched?: string
  message: string
}

/** 게시 200 응답 본문(계약 §6). */
export type PublishResult = {
  articleId: string
  status: AdminArticleStatus
  revisionNo: number
  /** 이 판을 검사한 규칙 파일의 버전 */
  checkVersion: string
  sectorMapVersion: string
  publishedAt: string
}

/** 게시 취소 200 응답 본문(계약 §7). 이미 초안이었으면 `unpublishedRevisionNo`가 `null`이다. */
export type UnpublishResult = {
  articleId: string
  status: AdminArticleStatus
  unpublishedRevisionNo: number | null
}

/** `400 INVALID_INPUT`의 `details.fields` 한 줄(`GlobalExceptionHandler`). */
export type AdminFieldViolation = {
  /** `blocks[5].text`처럼 Bean Validation 필드 표기다 */
  field: string
  message: string
}

/**
 * 배우기 관리가 내보내는 서버 오류 신호 이름이다(계약 §3·§5·§6).
 *
 * 공개 조회의 두 404와 합치지 않는다. 화면이 하는 일이 다르다 — 이쪽은 운영자가 고쳐서 다시
 * 보낼 수 있는 상태다. 이 union을 공용 `ServiceApiErrorCode`에 잇는 쪽은 `./api`다.
 */
export type LearningAdminApiErrorCode =
  | 'LEARNING_ARTICLE_NOT_FOUND'
  | 'LEARNING_ARTICLE_ID_TAKEN'
  | 'LEARNING_TERM_ID_TAKEN'
  | 'LEARNING_DRAFT_CHANGED'
  | 'LEARNING_PUBLISH_CHECK_FAILED'
