/**
 * spec 013 배우기 **관리** 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-admin-api.md`이며 필드는 그것과 1:1이다
 * (013 data-model §15). 공개 조회의 타입은 `./learning`에 있고 이 파일은 그것을 대신하지 않는다 —
 * **초안과 공개 판은 모양이 다르다.** 초안의 `term` 블록은 `termId`만 담고 문장은 `terms`에 있다
 * (data-model §4). 둘을 한 타입으로 합치면 화면이 아직 없는 문장을 있는 것처럼 다루게 된다.
 *
 * **(2판)** 초안은 형식(`V1`·`V2`)으로 갈린다(research R16). 두 형식은 같은 `type` 이름의 블록이라도
 * 필드가 다르다(V1 `paragraph`의 `text`, V2 `paragraph`의 `content`). 그래서 초안 타입을 `format`으로
 * 가르는 합집합으로 두고, 형식을 모르고는 블록을 읽지 않는다.
 *
 * **글 본문·용어 문장을 이 코드에 상수로 두지 않는다(013 FR-015).** 운영자가 서버에 넣은 값만 그린다.
 */

/** 글의 게시 상태. `PUBLISHED`일 때만 게시 취소가 있다(계약 §7). */
export type AdminArticleStatus = 'DRAFT' | 'PUBLISHED'

/** 초안 형식(research R16). V1은 9월 형식, V2는 새 기준 형식이다. */
export type ArticleFormat = 'V1' | 'V2'

/** 공개 중인 판의 종류. 게시되지 않은 글은 `null`로 온다(계약 §2). */
export type PublishedEdition = 'LEGACY' | 'CURRENT'

/** 게시·게시 취소 기록 한 줄(FR-018). `actorUserId`는 사용자 식별자이며 이메일·닉네임이 아니다. */
export type AdminPublicationRecord = {
  action: 'PUBLISH' | 'UNPUBLISH'
  /** 게시 취소가 아무것도 바꾸지 않았으면 `null`이다 */
  revisionNo: number | null
  actorUserId: number
  /** ISO 8601 시각 */
  occurredAt: string
}

/** 목록 항목의 초안 단계(계약 §2). `released`면 V2 글을 글마다 게시할 수 있다. */
export type AdminArticleStageRef = {
  stageKey: string
  number: number
  released: boolean
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
  /** **(2판)** 초안 형식 */
  format: ArticleFormat
  /** **(2판)** 초안 배치. 공개 배치와 다르면 다시 게시해야 공개된다(FR-023) */
  stage: AdminArticleStageRef
  stagePosition: number
  /** **(2판)** 게시되지 않았으면 `null`이다 */
  publishedEdition: PublishedEdition | null
  /**
   * **(2판)** 공개 중인 판의 제도 안내 기준 시점이 오래됐다(R37). 제도 안내가 없거나 게시되지 않았으면
   * `null`이다. `needsUpdate`처럼 `null`을 `false`로 접지 않는다.
   */
  needsReview: boolean | null
  /** **(2판)** 이 글의 초안에서 `transferFrom`이 있는 용어 수(R24) */
  pendingTransfers: number
}

/** 출처 표시 조각과 제도 안내의 출처(data-model §5-2). `label`은 저장할 때 비어 있어도 된다. */
export type DraftSourceRef = {
  label: string
  url: string
}

/**
 * 초안의 글자 조각(data-model §5-2). 공개 응답과 달리 언급 조각에 `owned`·`aliases`가 없다(계약 §4).
 * 조각 하나에는 `text`(글자), `term`+`text`(용어 언급), `source`(출처 표시) 가운데 하나만 있다.
 */
export type DraftRun =
  { text: string } | { term: string; text: string } | { source: DraftSourceRef }

/** 9월 형식(V1) 초안 블록. 공개 판과 달리 `term`은 `termId`만 담는다(계약 §4). */
export type AdminDraftBlockV1 =
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'term'; termId: string }
  | { type: 'hypothetical'; text: string }

/** 1판 코드가 쓰던 이름이다. V1 블록과 같다. */
export type AdminDraftBlock = AdminDraftBlockV1

/** 가상 도식의 계열 하나. `values` 길이는 `categories` 길이와 같아야 한다(서버 저장 검사). */
export type DraftDiagramSeries = { name: string; values: number[] }

/**
 * 새 기준 형식(V2) 초안 블록(data-model §5-3).
 *
 * `R`은 글자 조각 필드의 표현이다. 서버와 주고받는 초안은 조각 배열(`DraftRun[]`)이고, 관리 편집기와 원고는
 * 편집 문법 문자열(`string`)이다(research R26). 블록의 모양은 둘이 같고 글자 조각 필드만 다르다.
 */
export type AdminDraftBlockV2<R = DraftRun[]> =
  | { type: 'keySummary'; lines: R[] }
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; content: R }
  | { type: 'term'; termId: string }
  | { type: 'hypothetical'; content: R }
  | { type: 'misconception'; wrong: R; correction: R }
  | { type: 'list'; ordered: boolean; items: R[] }
  | { type: 'table'; caption: string; columns: string[]; rows: R[][] }
  | {
      type: 'diagram'
      kind: 'bar' | 'line'
      title: string
      unit: string
      categories: string[]
      series: DraftDiagramSeries[]
      description: string
    }
  | {
      type: 'institutionNote'
      content: R
      /** `YYYY-MM-DD` */
      asOf: string | null
      source: DraftSourceRef | null
    }

/** 초안 예시의 섹터 한 줄. `weightTenths`는 0.1%p 정수이고 1000이 100%다(002 계약과 같은 단위). */
export type AdminDraftExampleSector = {
  code: string
  weightTenths: number
}

/** 초안의 예시. 초안은 게시 전 검사를 받지 않으므로 통째로 없을 수 있다(계약 §5). */
export type AdminDraftExample = {
  sectors: AdminDraftExampleSector[]
}

/** 9월 판 글 끝 출처. V2 초안은 출처를 글자 옆 출처 표시로 두므로 이 목록이 비어 있어야 한다(계약 §5). */
export type AdminDraftSource = {
  label: string
  url: string | null
}

/** V1 용어 설명 한 줄. `termId`는 만든 뒤 바꾸지 않는다(research R4). */
export type AdminDraftTermV1 = {
  termId: string
  label: string
  sentence: string
}

/** 1판 코드가 쓰던 이름이다. V1 용어와 같다. */
export type AdminDraftTerm = AdminDraftTermV1

/** V2 용어 설명 한 줄(data-model §7-1). */
export type AdminDraftTermV2 = {
  termId: string
  label: string
  /** 0~4개, 각 1~40자 */
  aliases: string[]
  meaning: string
  reading: string
  caveat: string
  /**
   * 다른 글이 소유한 용어를 넘겨받는 중이면 그 글의 `articleId`다(data-model §7-2). 게시가 넘기기를
   * 마치면 서버가 비운다.
   */
  transferFrom: string | null
}

/** 확인 문제의 보기(data-model §6-1). 새 보기는 `choiceId`가 `null`이고 저장 응답에서 채워진다. */
export type DraftQuizChoice = {
  choiceId: string | null
  text: string
  correct: boolean
}

/** 확인 문제 하나(data-model §6-1). 새 문제는 `questionId`가 `null`이다. */
export type DraftQuizQuestion = {
  questionId: string | null
  prompt: string
  choices: DraftQuizChoice[]
  explanation: string
}

type DraftCommon = {
  title: string
  question: string
  readingOrder: number
  example: AdminDraftExample | null
  /** **(2판)** 초안 배치(계약 §5) */
  stageKey: string
  stagePosition: number
}

/** V1 초안. 확인 문제가 없다(계약 §4). */
export type AdminLearningDraftV1 = DraftCommon & {
  format: 'V1'
  blocks: AdminDraftBlockV1[]
  sources: AdminDraftSource[]
  terms: AdminDraftTermV1[]
  quiz: DraftQuizQuestion[]
}

/** V2 초안. `R`은 글자 조각 필드의 표현이다(`AdminDraftBlockV2` 참고). */
export type AdminLearningDraftV2<R = DraftRun[]> = DraftCommon & {
  format: 'V2'
  blocks: AdminDraftBlockV2<R>[]
  sources: AdminDraftSource[]
  terms: AdminDraftTermV2[]
  quiz: DraftQuizQuestion[]
}

/** 초안 한 벌. 저장 요청은 이 값을 통째로 보낸다 — 빠진 용어는 초안에서 빠진다(계약 §5). */
export type AdminLearningDraft = AdminLearningDraftV1 | AdminLearningDraftV2

/**
 * 관리 편집기가 들고 있는 초안이다. V2의 글자 조각 필드가 편집 문법 문자열이다(research R26). 원고 파일과
 * 탭 보관본도 이 모양이다. 저장할 때 `components/learning/admin/editableDraft.ts`가 조각으로 바꾼다.
 */
export type EditableDraft = AdminLearningDraftV1 | AdminLearningDraftV2<string>

/** `GET`·`PUT`·`POST`(만들기) 응답 본문(계약 §3·§4·§5). */
export type AdminLearningArticle = {
  articleId: string
  status: AdminArticleStatus
  /** 저장할 때마다 1 오른다. 저장·게시 요청이 마지막으로 읽은 값을 싣는다(R12) */
  draftVersion: number
  draft: AdminLearningDraft
  /** 공개 중인 판 번호. 초안이면 `null`이다 */
  publishedRevisionNo: number | null
  /** **(2판)** 공개 중인 판의 종류. 게시되지 않았으면 `null`이다 */
  publishedEdition: PublishedEdition | null
}

/** `POST /admin/learning/articles` 요청 본문(계약 §3). **(2판)** 새 글은 단계에 속한다. */
export type CreateArticleRequest = {
  articleId: string
  title: string
  stageKey: string
  stagePosition: number
}

/** `PUT /admin/learning/articles/{articleId}` 요청 본문(계약 §5). 초안을 통째로 바꾼다. */
export type SaveDraftRequest = AdminLearningDraft & {
  expectedDraftVersion: number
}

/**
 * 게시 전 검사에 걸린 위치(계약 §6, data-model §8-4). `part`에 따라 따라오는 필드가 다르다.
 *
 * **(2판)** `quiz`(확인 문제)와 `article`(글 전체, 단계 공개의 `FORMAT`·`PLACEMENT`)이 더해졌다. 이 목록에
 * 없는 `part`가 오면 `api/learningAdmin.ts`가 걸린 항목 전체를 읽지 않으므로, 서버가 위치를 늘리면 여기부터
 * 고친다.
 */
export type PublishCheckLocation = {
  part: 'title' | 'question' | 'example' | 'block' | 'term' | 'quiz' | 'article'
  /** `block`일 때의 순번(0부터). 본문 전체를 가리키면 없다. `quiz`에서는 식별자 없는 새 문제의 순번이다 */
  index?: number
  /** `term`일 때의 용어 식별자 */
  termId?: string
  /** 블록·용어·문제 안의 필드. 예: `lines[1]`, `wrong`, `rows[0][1]`, `prompt`, `explanation` */
  field?: string
  /** `quiz`일 때의 문제 식별자 */
  questionId?: string
  /** `quiz`일 때의 보기 식별자 */
  choiceId?: string
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

/** 같은 문제 판정으로 새 식별자를 받은 문제(data-model §6-3). */
export type RenamedQuestion = { from: string; to: string }

/** 넘겨받은 용어(data-model §7-2). `toArticleId`는 게시된 글이다. */
export type TransferredTerm = {
  termId: string
  fromArticleId: string
  toArticleId: string
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
  /** **(2판)** 새 판의 종류 */
  edition: PublishedEdition
  /** **(2판)** 이미 답한 사용자에게도 새 문제로 보이는 문제 */
  renamedQuestions: RenamedQuestion[]
  /** **(2판)** 이 게시가 넘겨받은 용어 */
  transferredTerms: TransferredTerm[]
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

/** 단계 목록의 글 한 편(계약 §9). */
export type StageAdminArticle = {
  articleId: string
  title: string
  format: ArticleFormat
  status: AdminArticleStatus
  publishedEdition: PublishedEdition | null
  draftVersion: number
  /** 이 단계 안 순서. `placementOnlyArticles`는 공개 배치의 값이다 */
  stagePosition: number
}

/** `GET /admin/learning/stages`의 단계 하나(계약 §9). */
export type StageAdminView = {
  stageKey: string
  number: number
  name: string
  levelLabel: string
  summary: string
  /** ISO 8601 시각. 공개 전이면 `null`이다 */
  releasedAt: string | null
  releasedByUserId: number | null
  /** 공개 전이고 앞 단계가 공개되었다. 검사 통과 여부는 미리 검사가 본다 */
  releasable: boolean
  /** 초안 단계가 이 단계인 글. 단계 공개에서 게시할 글이다 */
  articles: StageAdminArticle[]
  /** 공개 배치만 이 단계인 글. 단계 공개에서 `PLACEMENT`로 걸린다 */
  placementOnlyArticles: StageAdminArticle[]
}

/** 단계 공개를 막는 것(계약 §10). 서버가 늘려도 버리지 않도록 열린 문자열로 받는다. */
export type StageBlocker = 'ALREADY_RELEASED' | 'ORDER' | 'EMPTY' | (string & Record<never, never>)

/** 미리 검사의 글 한 편(계약 §10). */
export type StageReadinessArticle = {
  articleId: string
  /** 단계 공개 요청에 그대로 싣는다 */
  draftVersion: number
  failures: PublishCheckFailure[]
}

/** `GET /admin/learning/stages/{stageKey}/readiness` 응답 본문(계약 §10). 아무것도 쓰지 않는다. */
export type StageReadiness = {
  stageKey: string
  ready: boolean
  blockers: StageBlocker[]
  articles: StageReadinessArticle[]
  checkVersion: string
}

/** 단계 공개에서 게시된 글 한 편(계약 §11). */
export type StageReleasedArticle = {
  articleId: string
  revisionNo: number
  renamedQuestions: RenamedQuestion[]
}

/** `POST /admin/learning/stages/{stageKey}/release` 200 응답 본문(계약 §11). */
export type StageReleaseResult = {
  stageKey: string
  releasedAt: string
  published: StageReleasedArticle[]
  transferredTerms: TransferredTerm[]
  logEntryKey: string
  checkVersion: string
}

/** 단계 공개 422의 글 한 편. 걸린 글만 온다(계약 §11). */
export type StageCheckFailedArticle = {
  articleId: string
  failures: PublishCheckFailure[]
}

/** `409 LEARNING_STAGE_CHANGED`가 싣는 서버의 공개 집합(계약 §11). */
export type StageArticleVersion = {
  articleId: string
  draftVersion: number
}

/**
 * 원고 파일(research R26, `docs/learning-content/README.md`). 편집기 상태를 그대로 쓴 JSON이며 모양은 저장
 * 요청에서 `expectedDraftVersion`을 뺀 것과 같고 글자 조각 필드만 편집 문법 문자열이다. `articleId`는 다른
 * 글의 원고를 잘못 가져오는 일을 막는 표지다.
 */
export type ManuscriptFile = EditableDraft & { articleId: string }

/**
 * 배우기 관리가 내보내는 서버 오류 신호 이름이다(계약 §3·§5·§6·§9~§11).
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
  | 'LEARNING_TERM_TRANSFER_INVALID'
  | 'LEARNING_FORMAT_OUTDATED'
  | 'LEARNING_STAGE_NOT_FOUND'
  | 'LEARNING_STAGE_NOT_RELEASED'
  | 'LEARNING_STAGE_CHECK_FAILED'
  | 'LEARNING_STAGE_ALREADY_RELEASED'
  | 'LEARNING_STAGE_ORDER'
  | 'LEARNING_STAGE_EMPTY'
  | 'LEARNING_STAGE_CHANGED'
