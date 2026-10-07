import axios from 'axios'
import { ApiClientError, apiClient, normalizeApiError, runCsrfProtectedMutation } from './client'
import { readArticleContent, type LearningArticleContent } from './learning'
import type { ApiEnvelope, ApiErrorCode } from '@/types/api'
import type {
  AdminArticleStageRef,
  AdminArticleStatus,
  AdminDraftBlockV1,
  AdminDraftBlockV2,
  AdminDraftExample,
  AdminDraftSource,
  AdminDraftTermV1,
  AdminDraftTermV2,
  AdminFieldViolation,
  AdminLearningArticle,
  AdminLearningArticleSummary,
  AdminLearningDraft,
  AdminPublicationRecord,
  ArticleFormat,
  CreateArticleRequest,
  DraftDiagramSeries,
  DraftQuizChoice,
  DraftQuizQuestion,
  DraftRun,
  DraftSourceRef,
  PublishCheckFailure,
  PublishCheckLocation,
  PublishedEdition,
  PublishResult,
  RenamedQuestion,
  SaveDraftRequest,
  StageAdminArticle,
  StageAdminView,
  StageArticleVersion,
  StageCheckFailedArticle,
  StageReadiness,
  StageReadinessArticle,
  StageReleasedArticle,
  StageReleaseResult,
  TransferredTerm,
  UnpublishResult,
} from '@/types/learningAdmin'

/**
 * 배우기 관리 API(013 contracts/learning-admin-api.md)의 통로다.
 *
 * <p><b>서버가 보낸 `message`와 `details`를 그대로 보관한다.</b> 게시 전 검사 문장은 버전이 붙은 서버
 * 규칙 파일이 소유하므로(research R7) 화면이 문장을 새로 짓거나 줄이면 운영자가 무엇을 고쳐야
 * 하는지 알 수 없게 된다. 이 층은 갈래만 붙이고 문장은 건드리지 않는다.
 *
 * <p><b>예외를 던지지 않고 상태로 가른다.</b> `api/learning.ts`·`api/sectors.ts`와 같은 방식이다.
 * 고쳐서 다시 보낼 수 있는 거절(`draftChanged`·`checkFailed` 등)과 그렇지 않은 실패를 화면이 상태
 * 코드로 다시 판정하지 않게 한다.
 *
 * <p><b>스스로 다시 보내지 않는다.</b> 특히 `draftChanged`·`stageChanged`는 자동 병합도 자동 재전송도
 * 하지 않는다(R12·R23). 덮어쓰기를 막으려고 만든 거절을 화면이 자동으로 뚫으면 막은 뜻이 사라진다.
 *
 * <p>CSRF 헤더는 `apiClient`가 쿠키에서 붙이며 상태 변경 요청은 `runCsrfProtectedMutation`을 지난다.
 * `401`은 `api/client.ts`의 인증 필요 리스너(014)가 함께 받는다.
 */

const BASE_PATH = '/admin/learning'

export type LoadFailure = {
  status: 'failed'
  kind: 'api' | 'network' | 'unexpected'
  /** 서버가 보낸 이름 그대로다. 아는 이름이 아니어도 버리지 않는다 */
  code: ApiErrorCode | null
  message: string
}

/** 세션이 끝났다. 화면은 작성 중인 내용을 버리지 않고 014 재로그인 안내로 넘긴다(R12). */
type Unauthenticated = { status: 'unauthenticated' }

export type AdminAccessResult =
  | { status: 'admin' }
  /** `404 RESOURCE_NOT_FOUND`. 경로의 존재를 알리지 않는 응답이다(계약 §0) */
  | { status: 'denied' }
  | Unauthenticated
  | { status: 'failed'; message: string }

/**
 * 목록 결과. <b>`denied`를 두지 않는다.</b> 권한 판정은 `checkAdminAccess` 한 곳이며(계약 §1), 화면은 그
 * 결과가 `admin`일 때만 목록을 부른다. 그 뒤에 오는 404는 다시 시도할 수 있는 실패로 다룬다. 다시
 * 시도하면 권한 확인부터 다시 하므로 권한이 사라진 경우도 거기서 가려진다.
 */
export type AdminArticleListResult =
  { status: 'loaded'; articles: AdminLearningArticleSummary[] } | Unauthenticated | LoadFailure

export type AdminArticleResult =
  | { status: 'loaded'; article: AdminLearningArticle }
  | { status: 'notFound' }
  | Unauthenticated
  | LoadFailure

/** `404 LEARNING_STAGE_NOT_FOUND`(2판). 고른 단계가 그사이 없어졌다 */
type StageNotFound = { status: 'stageNotFound'; message: string }

export type CreateArticleResult =
  | { status: 'created'; article: AdminLearningArticle }
  /** `409 LEARNING_ARTICLE_ID_TAKEN`. 운영자가 다른 식별자를 고르면 된다 */
  | { status: 'idTaken'; message: string }
  | { status: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | StageNotFound
  | Unauthenticated
  | LoadFailure

/** 저장과 게시가 함께 쓰는 거절이다. 두 요청이 같은 조건으로 같은 코드를 돌려준다(계약 §5·§6). */
export type DraftRejection =
  /** `409 LEARNING_DRAFT_CHANGED`. 화면은 입력을 남긴 채 새로 불러오기를 안내한다(R12) */
  | {
      status: 'draftChanged'
      message: string
      currentDraftVersion: number | null
    }
  /**
   * `409 LEARNING_TERM_ID_TAKEN`. 다른 글이 쓰는 용어다. `ownerArticleId`가 있으면 화면이 '이 글로
   * 넘겨받기'를 제안할 수 있다(계약 §5, R24)
   */
  | {
      status: 'termIdTaken'
      message: string
      termId: string | null
      ownerArticleId: string | null
    }
  /**
   * `409 LEARNING_TERM_TRANSFER_INVALID`(2판). `transferFrom`이 지금 소유 글이 아니다. 소유 글이 없어졌으면
   * `ownerArticleId`가 `null`이다 — 화면은 이때 넘겨받기를 비우라고 안내한다(계약 §5)
   */
  | {
      status: 'termTransferInvalid'
      message: string
      termId: string | null
      ownerArticleId: string | null
    }
  /** `409 LEARNING_FORMAT_OUTDATED`(2판). LEGACY 판이 없는 글을 V1로 되돌렸거나 공개된 단계의 V1 초안을 게시했다 */
  | { status: 'formatOutdated'; message: string }
  | StageNotFound
  | { status: 'notFound' }
  | Unauthenticated

export type SaveDraftResult =
  | { status: 'saved'; article: AdminLearningArticle }
  | { status: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | DraftRejection
  | LoadFailure

export type PublishArticleResult =
  | { status: 'published'; result: PublishResult }
  /** `422 LEARNING_PUBLISH_CHECK_FAILED`. 아무것도 바뀌지 않았다(계약 §6) */
  | {
      status: 'checkFailed'
      message: string
      checkVersion: string | null
      failures: PublishCheckFailure[]
    }
  /** `409 LEARNING_STAGE_NOT_RELEASED`(2판). 공개 전 단계의 V2 글은 단계 공개로만 게시한다 */
  | { status: 'stageNotReleased'; message: string }
  /** `400 INVALID_INPUT`(2판). 정답이 바뀐 문제에 줄 새 식별자가 남지 않았다(계약 §6) */
  | { status: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | DraftRejection
  | LoadFailure

export type UnpublishArticleResult =
  | { status: 'unpublished'; result: UnpublishResult }
  | { status: 'notFound' }
  | Unauthenticated
  | LoadFailure

/** 미리 보기 결과(계약 §8). 게시 전이라 게시 시각이 없다. */
export type ArticlePreviewResult =
  | { status: 'loaded'; article: LearningArticleContent }
  | { status: 'notFound' }
  | Unauthenticated
  | LoadFailure

export type StageListResult =
  { status: 'loaded'; stages: StageAdminView[] } | Unauthenticated | LoadFailure

export type StageReadinessResult =
  { status: 'loaded'; readiness: StageReadiness } | StageNotFound | Unauthenticated | LoadFailure

export type StageReleaseOutcome =
  | { status: 'released'; result: StageReleaseResult }
  /**
   * `422 LEARNING_STAGE_CHECK_FAILED`. 글 하나라도 걸려 아무것도 바뀌지 않았다. 걸린 글만 온다(계약 §11).
   * 항목을 하나라도 읽지 못하면 `articles`가 비고 화면은 서버 문장만 보인다(`readFailures`와 같은 이유)
   */
  | {
      status: 'checkFailed'
      message: string
      checkVersion: string | null
      articles: StageCheckFailedArticle[]
    }
  /** `409 LEARNING_STAGE_ALREADY_RELEASED`·`LEARNING_STAGE_ORDER`·`LEARNING_STAGE_EMPTY` */
  | {
      status: 'rejected'
      reason: 'alreadyReleased' | 'order' | 'empty'
      message: string
    }
  /**
   * `409 LEARNING_STAGE_CHANGED`. 요청의 글 목록이나 초안 번호가 서버의 공개 집합과 다르다. 화면은 미리
   * 검사를 다시 하게 하고 이 목록으로 다시 보내지 않는다(R23)
   */
  | { status: 'changed'; message: string; articles: StageArticleVersion[] }
  | { status: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | StageNotFound
  | Unauthenticated
  | LoadFailure

const UNREADABLE_RESPONSE_MESSAGE = '응답을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(isString)
}

/** 배열의 모든 항목을 읽는다. 하나라도 읽지 못하면 `null`이다 — 일부만 남기면 다음 저장에서 사라진다. */
function readAll<T>(value: unknown, read: (item: unknown) => T | null): T[] | null {
  if (!Array.isArray(value)) return null
  const items: T[] = []
  for (const item of value) {
    const parsed = read(item)
    if (parsed === null) return null
    items.push(parsed)
  }
  return items
}

function readStatus(value: unknown): AdminArticleStatus | null {
  return value === 'DRAFT' || value === 'PUBLISHED' ? value : null
}

function readFormat(value: unknown): ArticleFormat | null {
  return value === 'V1' || value === 'V2' ? value : null
}

/** 게시되지 않은 글의 `null`과 읽지 못한 값(`'invalid'`)을 가른다. */
function readEdition(value: unknown): PublishedEdition | null | 'invalid' {
  if (value === null || value === undefined) return null
  return value === 'LEGACY' || value === 'CURRENT' ? value : 'invalid'
}

/** 판정하지 못한 `null`과 읽지 못한 값(`'invalid'`)을 가른다. `null`을 `false`로 접지 않는다(원칙 VI). */
function readOptionalBoolean(value: unknown): boolean | null | 'invalid' {
  if (value === null || value === undefined) return null
  return typeof value === 'boolean' ? value : 'invalid'
}

function readPublication(value: unknown): AdminPublicationRecord | null {
  if (!isRecord(value)) return null
  if (value.action !== 'PUBLISH' && value.action !== 'UNPUBLISH') return null
  if (typeof value.actorUserId !== 'number' || !isString(value.occurredAt)) return null
  const revisionNo = value.revisionNo
  if (revisionNo !== null && revisionNo !== undefined && typeof revisionNo !== 'number') return null
  return {
    action: value.action,
    revisionNo: revisionNo ?? null,
    actorUserId: value.actorUserId,
    occurredAt: value.occurredAt,
  }
}

function readStageRef(value: unknown): AdminArticleStageRef | null {
  if (!isRecord(value) || !isString(value.stageKey) || !isNumber(value.number)) return null
  if (typeof value.released !== 'boolean') return null
  return {
    stageKey: value.stageKey,
    number: value.number,
    released: value.released,
  }
}

function readSummary(value: unknown): AdminLearningArticleSummary | null {
  if (!isRecord(value) || !isString(value.articleId) || !isString(value.title)) return null
  if (typeof value.readingOrder !== 'number' || typeof value.draftVersion !== 'number') return null
  if (typeof value.hasUnpublishedChanges !== 'boolean') return null
  const status = readStatus(value.status)
  if (!status) return null

  const needsUpdate = readOptionalBoolean(value.needsUpdate)
  const needsReview = readOptionalBoolean(value.needsReview)
  if (needsUpdate === 'invalid' || needsReview === 'invalid') return null

  const hasPublication = value.lastPublication !== null && value.lastPublication !== undefined
  const lastPublication = hasPublication ? readPublication(value.lastPublication) : null
  if (hasPublication && !lastPublication) return null

  const format = readFormat(value.format)
  const stage = readStageRef(value.stage)
  const publishedEdition = readEdition(value.publishedEdition)
  if (!format || !stage || publishedEdition === 'invalid') return null
  if (!isNumber(value.stagePosition) || !isNumber(value.pendingTransfers)) return null

  return {
    articleId: value.articleId,
    title: value.title,
    readingOrder: value.readingOrder,
    status,
    draftVersion: value.draftVersion,
    hasUnpublishedChanges: value.hasUnpublishedChanges,
    needsUpdate,
    lastPublication,
    format,
    stage,
    stagePosition: value.stagePosition,
    publishedEdition,
    needsReview,
    pendingTransfers: value.pendingTransfers,
  }
}

function readSourceRef(value: unknown): DraftSourceRef | null {
  if (!isRecord(value) || !isString(value.label) || !isString(value.url)) return null
  return { label: value.label, url: value.url }
}

/**
 * 초안의 글자 조각 하나를 읽는다(data-model §5-2). 조각 하나에는 키 하나만 있다 — 글자(`text`), 용어
 * 언급(`term`+`text`), 출처 표시(`source`). 서버가 `null` 키를 실어 보내도 없는 키로 본다.
 */
function readRun(value: unknown): DraftRun | null {
  if (!isRecord(value)) return null
  const has = (key: string) => value[key] !== null && value[key] !== undefined
  if (has('source')) {
    if (has('text') || has('term')) return null
    const source = readSourceRef(value.source)
    return source ? { source } : null
  }
  if (!isString(value.text)) return null
  if (has('term')) return isString(value.term) ? { term: value.term, text: value.text } : null
  return { text: value.text }
}

function readRuns(value: unknown): DraftRun[] | null {
  return readAll(value, readRun)
}

/**
 * V1 초안 블록 하나를 읽는다.
 *
 * <p>공개 조회(`api/learning.ts`)와 달리 <b>모르는 `type`을 그대로 넘기지 않는다.</b> 이 값은 편집기가
 * 고쳐서 다시 저장하는 값이라, 읽지 못한 블록을 통과시키면 다음 저장에서 그 블록이 조용히 사라지거나
 * 400을 받는다. 서버도 알 수 없는 `type`을 저장에서 거절한다(data-model §5).
 */
function readBlockV1(value: unknown): AdminDraftBlockV1 | null {
  if (!isRecord(value) || !isString(value.type)) return null
  switch (value.type) {
    case 'heading':
    case 'paragraph':
    case 'hypothetical':
      return isString(value.text) ? { type: value.type, text: value.text } : null
    case 'term':
      return isString(value.termId) ? { type: 'term', termId: value.termId } : null
    default:
      return null
  }
}

function readDiagramSeries(value: unknown): DraftDiagramSeries | null {
  if (!isRecord(value) || !isString(value.name) || !Array.isArray(value.values)) return null
  if (!value.values.every(isNumber)) return null
  return { name: value.name, values: [...value.values] }
}

/** V2 초안 블록 하나를 읽는다(data-model §5-3). 모르는 `type`은 V1과 같은 이유로 읽지 않는다. */
function readBlockV2(value: unknown): AdminDraftBlockV2 | null {
  if (!isRecord(value) || !isString(value.type)) return null
  switch (value.type) {
    case 'keySummary': {
      const lines = readAll(value.lines, readRuns)
      return lines ? { type: 'keySummary', lines } : null
    }
    case 'heading':
      return isString(value.text) ? { type: 'heading', text: value.text } : null
    case 'paragraph':
    case 'hypothetical': {
      const content = readRuns(value.content)
      return content ? { type: value.type, content } : null
    }
    case 'term':
      return isString(value.termId) ? { type: 'term', termId: value.termId } : null
    case 'misconception': {
      const wrong = readRuns(value.wrong)
      const correction = readRuns(value.correction)
      return wrong && correction ? { type: 'misconception', wrong, correction } : null
    }
    case 'list': {
      const items = readAll(value.items, readRuns)
      if (!items || typeof value.ordered !== 'boolean') return null
      return { type: 'list', ordered: value.ordered, items }
    }
    case 'table': {
      const rows = readAll(value.rows, (row) => readAll(row, readRuns))
      if (!rows || !isString(value.caption) || !isStringArray(value.columns)) return null
      return {
        type: 'table',
        caption: value.caption,
        columns: [...value.columns],
        rows,
      }
    }
    case 'diagram': {
      const series = readAll(value.series, readDiagramSeries)
      if (value.kind !== 'bar' && value.kind !== 'line') return null
      if (!series || !isString(value.title) || !isString(value.unit)) return null
      if (!isStringArray(value.categories) || !isString(value.description)) return null
      return {
        type: 'diagram',
        kind: value.kind,
        title: value.title,
        unit: value.unit,
        categories: [...value.categories],
        series,
        description: value.description,
      }
    }
    case 'institutionNote': {
      const content = readRuns(value.content)
      const asOf = value.asOf ?? null
      const source =
        value.source === null || value.source === undefined ? null : readSourceRef(value.source)
      if (!content || !(asOf === null || isString(asOf))) return null
      if (value.source !== null && value.source !== undefined && !source) return null
      return { type: 'institutionNote', content, asOf, source }
    }
    default:
      return null
  }
}

/** 예시는 통째로 없을 수 있다. 없는 것(`null`)과 읽지 못한 것(`'invalid'`)을 가른다. */
function readExample(value: unknown): AdminDraftExample | null | 'invalid' {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || !Array.isArray(value.sectors)) return 'invalid'
  const sectors = []
  for (const item of value.sectors) {
    if (!isRecord(item) || !isString(item.code) || typeof item.weightTenths !== 'number') {
      return 'invalid'
    }
    sectors.push({ code: item.code, weightTenths: item.weightTenths })
  }
  return { sectors }
}

function readSource(value: unknown): AdminDraftSource | null {
  if (!isRecord(value) || !isString(value.label)) return null
  // 계약이 "url은 없을 수 있다"고 정했다. 키가 없는 것과 null을 같은 뜻으로 받는다.
  if (value.url !== undefined && value.url !== null && !isString(value.url)) return null
  return { label: value.label, url: value.url ?? null }
}

function readTermV1(value: unknown): AdminDraftTermV1 | null {
  if (!isRecord(value) || !isString(value.termId) || !isString(value.label)) return null
  return isString(value.sentence)
    ? { termId: value.termId, label: value.label, sentence: value.sentence }
    : null
}

function readTermV2(value: unknown): AdminDraftTermV2 | null {
  if (!isRecord(value) || !isString(value.termId) || !isString(value.label)) return null
  const aliases = value.aliases ?? []
  if (!isStringArray(aliases)) return null
  if (!isString(value.meaning) || !isString(value.reading) || !isString(value.caveat)) return null
  const transferFrom = value.transferFrom ?? null
  if (!(transferFrom === null || isString(transferFrom))) return null
  return {
    termId: value.termId,
    label: value.label,
    aliases: [...aliases],
    meaning: value.meaning,
    reading: value.reading,
    caveat: value.caveat,
    transferFrom,
  }
}

function readQuizChoice(value: unknown): DraftQuizChoice | null {
  if (!isRecord(value) || !isString(value.text) || typeof value.correct !== 'boolean') return null
  const choiceId = value.choiceId ?? null
  if (!(choiceId === null || isString(choiceId))) return null
  return { choiceId, text: value.text, correct: value.correct }
}

function readQuizQuestion(value: unknown): DraftQuizQuestion | null {
  if (!isRecord(value) || !isString(value.prompt) || !isString(value.explanation)) return null
  const questionId = value.questionId ?? null
  const choices = readAll(value.choices, readQuizChoice)
  if (!choices || !(questionId === null || isString(questionId))) return null
  return {
    questionId,
    prompt: value.prompt,
    choices,
    explanation: value.explanation,
  }
}

/**
 * 초안을 읽는다. <b>`format`을 먼저 읽는다.</b> 같은 `type` 이름의 블록이 형식마다 필드가 달라 형식을
 * 모르고는 블록을 읽을 수 없다(R16).
 */
function readDraft(value: unknown): AdminLearningDraft | null {
  if (!isRecord(value) || !isString(value.title) || !isString(value.question)) return null
  if (typeof value.readingOrder !== 'number') return null
  const format = readFormat(value.format)
  if (!format || !isString(value.stageKey) || !isNumber(value.stagePosition)) return null

  const sources = readAll(value.sources, readSource)
  const quiz = readAll(value.quiz ?? [], readQuizQuestion)
  const example = readExample(value.example)
  if (!sources || !quiz || example === 'invalid') return null

  const common = {
    title: value.title,
    question: value.question,
    readingOrder: value.readingOrder,
    example,
    stageKey: value.stageKey,
    stagePosition: value.stagePosition,
    sources,
    quiz,
  }

  if (format === 'V1') {
    const blocks = readAll(value.blocks, readBlockV1)
    const terms = readAll(value.terms, readTermV1)
    return blocks && terms ? { ...common, format, blocks, terms } : null
  }
  const blocks = readAll(value.blocks, readBlockV2)
  const terms = readAll(value.terms, readTermV2)
  return blocks && terms ? { ...common, format, blocks, terms } : null
}

function readArticle(value: unknown): AdminLearningArticle | null {
  if (!isRecord(value) || !isString(value.articleId)) return null
  if (typeof value.draftVersion !== 'number') return null
  const status = readStatus(value.status)
  if (!status) return null
  const draft = readDraft(value.draft)
  if (!draft) return null
  const publishedRevisionNo = value.publishedRevisionNo
  if (
    publishedRevisionNo !== null &&
    publishedRevisionNo !== undefined &&
    typeof publishedRevisionNo !== 'number'
  ) {
    return null
  }
  const publishedEdition = readEdition(value.publishedEdition)
  if (publishedEdition === 'invalid') return null
  return {
    articleId: value.articleId,
    status,
    draftVersion: value.draftVersion,
    draft,
    publishedRevisionNo: publishedRevisionNo ?? null,
    publishedEdition,
  }
}

function readRenamedQuestion(value: unknown): RenamedQuestion | null {
  if (!isRecord(value) || !isString(value.from) || !isString(value.to)) return null
  return { from: value.from, to: value.to }
}

function readTransferredTerm(value: unknown): TransferredTerm | null {
  if (!isRecord(value) || !isString(value.termId)) return null
  if (!isString(value.fromArticleId) || !isString(value.toArticleId)) return null
  return {
    termId: value.termId,
    fromArticleId: value.fromArticleId,
    toArticleId: value.toArticleId,
  }
}

function readPublishResult(value: unknown): PublishResult | null {
  if (!isRecord(value) || !isString(value.articleId)) return null
  const status = readStatus(value.status)
  if (!status || typeof value.revisionNo !== 'number') return null
  if (!isString(value.checkVersion) || !isString(value.sectorMapVersion)) return null
  if (!isString(value.publishedAt)) return null
  const edition = readEdition(value.edition)
  const renamedQuestions = readAll(value.renamedQuestions, readRenamedQuestion)
  const transferredTerms = readAll(value.transferredTerms, readTransferredTerm)
  if (edition === null || edition === 'invalid' || !renamedQuestions || !transferredTerms) {
    return null
  }
  return {
    articleId: value.articleId,
    status,
    revisionNo: value.revisionNo,
    checkVersion: value.checkVersion,
    sectorMapVersion: value.sectorMapVersion,
    publishedAt: value.publishedAt,
    edition,
    renamedQuestions,
    transferredTerms,
  }
}

function readUnpublishResult(value: unknown): UnpublishResult | null {
  if (!isRecord(value) || !isString(value.articleId)) return null
  const status = readStatus(value.status)
  if (!status) return null
  const revisionNo = value.unpublishedRevisionNo
  if (revisionNo !== null && revisionNo !== undefined && typeof revisionNo !== 'number') return null
  return {
    articleId: value.articleId,
    status,
    unpublishedRevisionNo: revisionNo ?? null,
  }
}

const CHECK_LOCATION_PARTS: readonly PublishCheckLocation['part'][] = [
  'title',
  'question',
  'example',
  'block',
  'term',
  'quiz',
  'article',
]

function readLocation(value: unknown): PublishCheckLocation | null {
  if (!isRecord(value)) return null
  const part = CHECK_LOCATION_PARTS.find((candidate) => candidate === value.part)
  if (!part) return null
  const location: PublishCheckLocation = { part }
  if (typeof value.index === 'number') location.index = value.index
  if (isString(value.termId)) location.termId = value.termId
  if (isString(value.field)) location.field = value.field
  if (isString(value.questionId)) location.questionId = value.questionId
  if (isString(value.choiceId)) location.choiceId = value.choiceId
  return location
}

/** 걸린 검사 목록을 읽는다. 하나라도 읽지 못하면 `null`이다. */
function readFailuresStrict(value: unknown): PublishCheckFailure[] | null {
  return readAll(value, (item) => {
    if (!isRecord(item) || !isString(item.check) || !isString(item.message)) return null
    const location = readLocation(item.location)
    if (!location) return null
    const failure: PublishCheckFailure = {
      check: item.check,
      location,
      message: item.message,
    }
    if (isString(item.matched)) failure.matched = item.matched
    return failure
  })
}

/**
 * 거절 응답의 걸린 검사 목록을 읽는다.
 *
 * <p><b>읽지 못한 항목만 빼지 않고 목록 전체를 비운다.</b> 일부만 보이면 운영자가 나머지를 고쳤다고
 * 믿고 다시 게시를 누른다. 그러면 같은 422를 다시 받으면서도 무엇이 빠졌는지 모른다. 목록이 비면
 * 화면은 항목 자리를 만들지 않고 서버 문장만 보인다.
 *
 * <p>미리 검사(200)에서는 이 함수를 쓰지 않는다. 거기서 빈 목록은 '걸린 것 없음'이라는 뜻이라, 읽지 못한
 * 것을 빈 목록으로 접으면 통과로 보인다(원칙 VI). 미리 검사는 `readFailuresStrict`로 응답 전체를 실패로 둔다.
 */
function readFailures(value: unknown): PublishCheckFailure[] {
  return readFailuresStrict(value) ?? []
}

function readStageArticle(value: unknown): StageAdminArticle | null {
  if (!isRecord(value) || !isString(value.articleId) || !isString(value.title)) return null
  const format = readFormat(value.format)
  const status = readStatus(value.status)
  const publishedEdition = readEdition(value.publishedEdition)
  if (!format || !status || publishedEdition === 'invalid') return null
  if (!isNumber(value.draftVersion) || !isNumber(value.stagePosition)) return null
  return {
    articleId: value.articleId,
    title: value.title,
    format,
    status,
    publishedEdition,
    draftVersion: value.draftVersion,
    stagePosition: value.stagePosition,
  }
}

function readStage(value: unknown): StageAdminView | null {
  if (!isRecord(value) || !isString(value.stageKey) || !isNumber(value.number)) return null
  if (!isString(value.name) || !isString(value.levelLabel) || !isString(value.summary)) return null
  if (typeof value.releasable !== 'boolean') return null
  const releasedAt = value.releasedAt ?? null
  const releasedByUserId = value.releasedByUserId ?? null
  if (!(releasedAt === null || isString(releasedAt))) return null
  if (!(releasedByUserId === null || isNumber(releasedByUserId))) return null
  const articles = readAll(value.articles, readStageArticle)
  const placementOnlyArticles = readAll(value.placementOnlyArticles ?? [], readStageArticle)
  if (!articles || !placementOnlyArticles) return null
  return {
    stageKey: value.stageKey,
    number: value.number,
    name: value.name,
    levelLabel: value.levelLabel,
    summary: value.summary,
    releasedAt,
    releasedByUserId,
    releasable: value.releasable,
    articles,
    placementOnlyArticles,
  }
}

function readReadinessArticle(value: unknown): StageReadinessArticle | null {
  if (!isRecord(value) || !isString(value.articleId) || !isNumber(value.draftVersion)) return null
  const failures = readFailuresStrict(value.failures)
  return failures
    ? { articleId: value.articleId, draftVersion: value.draftVersion, failures }
    : null
}

function readReadiness(value: unknown): StageReadiness | null {
  if (!isRecord(value) || !isString(value.stageKey) || typeof value.ready !== 'boolean') return null
  if (!isStringArray(value.blockers) || !isString(value.checkVersion)) return null
  const articles = readAll(value.articles, readReadinessArticle)
  if (!articles) return null
  return {
    stageKey: value.stageKey,
    ready: value.ready,
    blockers: [...value.blockers],
    articles,
    checkVersion: value.checkVersion,
  }
}

function readReleasedArticle(value: unknown): StageReleasedArticle | null {
  if (!isRecord(value) || !isString(value.articleId) || !isNumber(value.revisionNo)) return null
  const renamedQuestions = readAll(value.renamedQuestions, readRenamedQuestion)
  return renamedQuestions
    ? {
        articleId: value.articleId,
        revisionNo: value.revisionNo,
        renamedQuestions,
      }
    : null
}

function readReleaseResult(value: unknown): StageReleaseResult | null {
  if (!isRecord(value) || !isString(value.stageKey) || !isString(value.releasedAt)) return null
  if (!isString(value.logEntryKey) || !isString(value.checkVersion)) return null
  const published = readAll(value.published, readReleasedArticle)
  const transferredTerms = readAll(value.transferredTerms, readTransferredTerm)
  if (!published || !transferredTerms) return null
  return {
    stageKey: value.stageKey,
    releasedAt: value.releasedAt,
    published,
    transferredTerms,
    logEntryKey: value.logEntryKey,
    checkVersion: value.checkVersion,
  }
}

/** 단계 공개 422의 글별 항목. 하나라도 읽지 못하면 빈 목록이다(`readFailures`와 같은 이유). */
function readCheckFailedArticles(value: unknown): StageCheckFailedArticle[] {
  return (
    readAll(value, (item) => {
      if (!isRecord(item) || !isString(item.articleId)) return null
      const failures = readFailuresStrict(item.failures)
      return failures ? { articleId: item.articleId, failures } : null
    }) ?? []
  )
}

function readArticleVersions(value: unknown): StageArticleVersion[] {
  return (
    readAll(value, (item) =>
      isRecord(item) && isString(item.articleId) && isNumber(item.draftVersion)
        ? { articleId: item.articleId, draftVersion: item.draftVersion }
        : null,
    ) ?? []
  )
}

function readFieldViolations(value: unknown): AdminFieldViolation[] {
  if (!Array.isArray(value)) return []
  const fields: AdminFieldViolation[] = []
  for (const item of value) {
    if (isRecord(item) && isString(item.field) && isString(item.message)) {
      fields.push({ field: item.field, message: item.message })
    }
  }
  return fields
}

/**
 * 오류 본문의 `details`를 꺼낸다.
 *
 * <p>`normalizeApiError`는 `code`·`message`만 남기므로 `details`는 원래 axios 오류에서 읽는다.
 * `runCsrfProtectedMutation`이 한 번 감싸 던진 경우까지 따라간다(`api/result.ts`와 같은 이유).
 */
function readDetails(error: unknown): Record<string, unknown> | null {
  const source = error instanceof ApiClientError ? error.cause : error
  if (!axios.isAxiosError(source)) return null
  const body = source.response?.data
  if (!isRecord(body) || !isRecord(body.error)) return null
  return isRecord(body.error.details) ? body.error.details : null
}

/** 공통 봉투에 싸여 있으면 벗긴다. 실패 봉투가 200으로 오면 오류로 바꿔 던진다. */
function unwrap<T>(body: ApiEnvelope<T> | T): T {
  if (isRecord(body) && 'success' in body && 'data' in body) {
    const envelope = body as ApiEnvelope<T>
    if (!envelope.success) {
      throw new ApiClientError('api', envelope.error.message, {
        code: envelope.error.code,
      })
    }
    return envelope.data
  }
  return body as T
}

function unreadable(): LoadFailure {
  return {
    status: 'failed',
    kind: 'unexpected',
    code: null,
    message: UNREADABLE_RESPONSE_MESSAGE,
  }
}

function toFailure(error: ApiClientError): LoadFailure {
  return {
    status: 'failed',
    kind: error.kind,
    code: error.code,
    message: error.message,
  }
}

function statusOf(error: unknown): number | null {
  const source = error instanceof ApiClientError ? error.cause : error
  return axios.isAxiosError(source) ? (source.response?.status ?? null) : null
}

function articlePath(articleId: string, suffix = ''): string {
  return `${BASE_PATH}/articles/${encodeURIComponent(articleId)}${suffix}`
}

function stagePath(stageKey: string, suffix: string): string {
  return `${BASE_PATH}/stages/${encodeURIComponent(stageKey)}${suffix}`
}

function readNumber(details: Record<string, unknown> | null, key: string): number | null {
  const value = details?.[key]
  return typeof value === 'number' ? value : null
}

function readText(details: Record<string, unknown> | null, key: string): string | null {
  const value = details?.[key]
  return isString(value) ? value : null
}

function readInvalid(error: unknown, message: string) {
  return {
    status: 'invalid' as const,
    message,
    fields: readFieldViolations(readDetails(error)?.fields),
  }
}

/** 저장과 게시가 공유하는 거절을 읽는다. 해당하지 않으면 `null`이고 부르는 쪽이 이어서 가른다. */
function readDraftRejection(error: unknown): DraftRejection | null {
  if (statusOf(error) === 401) return { status: 'unauthenticated' }
  const normalized = normalizeApiError(error)
  const details = readDetails(error)

  if (normalized.is('LEARNING_DRAFT_CHANGED')) {
    return {
      status: 'draftChanged',
      message: normalized.message,
      currentDraftVersion: readNumber(details, 'currentDraftVersion'),
    }
  }
  if (normalized.is('LEARNING_TERM_ID_TAKEN')) {
    return {
      status: 'termIdTaken',
      message: normalized.message,
      termId: readText(details, 'termId'),
      ownerArticleId: readText(details, 'ownerArticleId'),
    }
  }
  if (normalized.is('LEARNING_TERM_TRANSFER_INVALID')) {
    return {
      status: 'termTransferInvalid',
      message: normalized.message,
      termId: readText(details, 'termId'),
      // 지금 소유 글이 없으면 키가 없다(계약 §5). 화면은 이때 넘겨받기를 비우라고 안내한다.
      ownerArticleId: readText(details, 'ownerArticleId'),
    }
  }
  if (normalized.is('LEARNING_FORMAT_OUTDATED')) {
    return { status: 'formatOutdated', message: normalized.message }
  }
  if (normalized.is('LEARNING_STAGE_NOT_FOUND')) {
    return { status: 'stageNotFound', message: normalized.message }
  }
  if (normalized.is('LEARNING_ARTICLE_NOT_FOUND')) return { status: 'notFound' }
  return null
}

/**
 * 관리 권한을 확인한다(계약 §1).
 *
 * <p><b>`admin: false`인 200은 없다.</b> 권한이 없으면 404이며, 화면은 200일 때만 관리 화면을 그린다.
 */
export async function checkAdminAccess(signal?: AbortSignal): Promise<AdminAccessResult> {
  try {
    await apiClient.get<ApiEnvelope<{ admin: boolean }>>(`${BASE_PATH}/access`, { signal })
    return { status: 'admin' }
  } catch (error) {
    const status = statusOf(error)
    if (status === 401) return { status: 'unauthenticated' }
    if (status === 404) return { status: 'denied' }
    return { status: 'failed', message: normalizeApiError(error).message }
  }
}

/** 초안을 포함한 글 목록을 받아 온다(계약 §2). */
export async function fetchAdminArticles(signal?: AbortSignal): Promise<AdminArticleListResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<{ articles: unknown[] }>>(
      `${BASE_PATH}/articles`,
      { signal },
    )
    const body = unwrap(response.data)
    if (!isRecord(body)) return unreadable()
    const articles = readAll(body.articles, readSummary)
    return articles ? { status: 'loaded', articles } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    return toFailure(normalizeApiError(error))
  }
}

/** 글을 만든다(계약 §3). 새 글은 `DRAFT`, `draftVersion: 0`, `format: V2`다. */
export async function createArticle(request: CreateArticleRequest): Promise<CreateArticleResult> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ApiEnvelope<AdminLearningArticle>>(`${BASE_PATH}/articles`, request),
    )
    const article = readArticle(unwrap(response.data))
    return article ? { status: 'created', article } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_ARTICLE_ID_TAKEN')) {
      return { status: 'idTaken', message: normalized.message }
    }
    if (normalized.is('LEARNING_STAGE_NOT_FOUND')) {
      return { status: 'stageNotFound', message: normalized.message }
    }
    if (normalized.is('INVALID_INPUT')) return readInvalid(error, normalized.message)
    return toFailure(normalized)
  }
}

/** 초안 하나를 받아 온다(계약 §4). */
export async function fetchAdminArticle(
  articleId: string,
  signal?: AbortSignal,
): Promise<AdminArticleResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<AdminLearningArticle>>(
      articlePath(articleId),
      {
        signal,
      },
    )
    const article = readArticle(unwrap(response.data))
    return article ? { status: 'loaded', article } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_ARTICLE_NOT_FOUND')) return { status: 'notFound' }
    return toFailure(normalized)
  }
}

/**
 * 초안을 통째로 저장한다(계약 §5).
 *
 * <p><b>`expectedDraftVersion`은 화면이 마지막으로 읽은 값이다.</b> 서버 값과 다르면 거절되며 이 함수는
 * 새 값으로 다시 보내지 않는다(R12). 병합도 하지 않는다. 어느 쪽이 맞는지는 운영자가 정한다.
 */
export async function saveDraft(
  articleId: string,
  request: SaveDraftRequest,
): Promise<SaveDraftResult> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.put<ApiEnvelope<AdminLearningArticle>>(articlePath(articleId), request),
    )
    const article = readArticle(unwrap(response.data))
    return article ? { status: 'saved', article } : unreadable()
  } catch (error) {
    const rejection = readDraftRejection(error)
    if (rejection) return rejection
    const normalized = normalizeApiError(error)
    if (normalized.is('INVALID_INPUT')) return readInvalid(error, normalized.message)
    return toFailure(normalized)
  }
}

/**
 * 게시한다(계약 §6).
 *
 * <p><b>422는 연결 실패가 아니라 검사 결과다.</b> 걸린 항목을 모두 싣고 아무것도 바꾸지 않는다.
 * 화면은 항목과 문장을 그대로 보인다(R7).
 */
export async function publishArticle(
  articleId: string,
  expectedDraftVersion: number,
): Promise<PublishArticleResult> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ApiEnvelope<PublishResult>>(articlePath(articleId, '/publish'), {
        expectedDraftVersion,
      }),
    )
    const result = readPublishResult(unwrap(response.data))
    return result ? { status: 'published', result } : unreadable()
  } catch (error) {
    const rejection = readDraftRejection(error)
    if (rejection) return rejection
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_PUBLISH_CHECK_FAILED')) {
      const details = readDetails(error)
      return {
        status: 'checkFailed',
        message: normalized.message,
        checkVersion: readText(details, 'checkVersion'),
        failures: readFailures(details?.failures),
      }
    }
    if (normalized.is('LEARNING_STAGE_NOT_RELEASED')) {
      return { status: 'stageNotReleased', message: normalized.message }
    }
    if (normalized.is('INVALID_INPUT')) return readInvalid(error, normalized.message)
    return toFailure(normalized)
  }
}

/** 게시를 취소한다(계약 §7). 이미 초안이면 아무것도 바꾸지 않고 `unpublishedRevisionNo`가 `null`이다. */
export async function unpublishArticle(articleId: string): Promise<UnpublishArticleResult> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ApiEnvelope<UnpublishResult>>(articlePath(articleId, '/unpublish')),
    )
    const result = readUnpublishResult(unwrap(response.data))
    return result ? { status: 'unpublished', result } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_ARTICLE_NOT_FOUND')) return { status: 'notFound' }
    return toFailure(normalized)
  }
}

/**
 * 지금 서버에 저장된 초안을 독자 화면 모양으로 받아 온다(계약 §8). 공개 글과 같은 해석기로 읽고 게시 시각만
 * 읽지 않는다. 게시 전 검사는 하지 않는다.
 */
export async function fetchArticlePreview(
  articleId: string,
  signal?: AbortSignal,
): Promise<ArticlePreviewResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>(articlePath(articleId, '/preview'), {
      signal,
    })
    const article = readArticleContent(unwrap(response.data))
    return article ? { status: 'loaded', article } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_ARTICLE_NOT_FOUND')) return { status: 'notFound' }
    return toFailure(normalized)
  }
}

/** 단계 목록과 단계마다 공개될 글을 받아 온다(계약 §9). */
export async function fetchAdminStages(signal?: AbortSignal): Promise<StageListResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>(`${BASE_PATH}/stages`, { signal })
    const body = unwrap(response.data)
    if (!isRecord(body)) return unreadable()
    const stages = readAll(body.stages, readStage)
    return stages ? { status: 'loaded', stages } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    return toFailure(normalizeApiError(error))
  }
}

/**
 * 단계 공개와 같은 판정을 하되 아무것도 쓰지 않는다(계약 §10, R23).
 *
 * <p><b>항목을 하나라도 읽지 못하면 응답 전체를 읽지 못한 것으로 둔다.</b> 이 응답에서 빈 목록은 '걸린 것
 * 없음'이라, 읽지 못한 항목을 빼면 공개해도 되는 것처럼 보인다(원칙 VI).
 */
export async function fetchStageReadiness(
  stageKey: string,
  signal?: AbortSignal,
): Promise<StageReadinessResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>(stagePath(stageKey, '/readiness'), {
      signal,
    })
    const readiness = readReadiness(unwrap(response.data))
    return readiness ? { status: 'loaded', readiness } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    if (normalized.is('LEARNING_STAGE_NOT_FOUND')) {
      return { status: 'stageNotFound', message: normalized.message }
    }
    return toFailure(normalized)
  }
}

/**
 * 단계를 공개한다(계약 §11). 되돌리는 엔드포인트는 없다.
 *
 * <p><b>`articles`는 미리 검사가 돌려준 목록과 초안 번호 그대로다.</b> 서버가 그사이 바뀌었으면 409
 * `LEARNING_STAGE_CHANGED`로 거절하고, 이 함수는 서버가 알려 준 목록으로 다시 보내지 않는다(R23).
 */
export async function releaseStage(
  stageKey: string,
  articles: readonly StageArticleVersion[],
): Promise<StageReleaseOutcome> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ApiEnvelope<unknown>>(stagePath(stageKey, '/release'), {
        articles: articles.map((article) => ({
          articleId: article.articleId,
          expectedDraftVersion: article.draftVersion,
        })),
      }),
    )
    const result = readReleaseResult(unwrap(response.data))
    return result ? { status: 'released', result } : unreadable()
  } catch (error) {
    if (statusOf(error) === 401) return { status: 'unauthenticated' }
    const normalized = normalizeApiError(error)
    const details = readDetails(error)
    if (normalized.is('LEARNING_STAGE_CHECK_FAILED')) {
      return {
        status: 'checkFailed',
        message: normalized.message,
        checkVersion: readText(details, 'checkVersion'),
        articles: readCheckFailedArticles(details?.articles),
      }
    }
    if (normalized.is('LEARNING_STAGE_ALREADY_RELEASED')) {
      return {
        status: 'rejected',
        reason: 'alreadyReleased',
        message: normalized.message,
      }
    }
    if (normalized.is('LEARNING_STAGE_ORDER')) {
      return {
        status: 'rejected',
        reason: 'order',
        message: normalized.message,
      }
    }
    if (normalized.is('LEARNING_STAGE_EMPTY')) {
      return {
        status: 'rejected',
        reason: 'empty',
        message: normalized.message,
      }
    }
    if (normalized.is('LEARNING_STAGE_CHANGED')) {
      return {
        status: 'changed',
        message: normalized.message,
        articles: readArticleVersions(details?.articles),
      }
    }
    if (normalized.is('LEARNING_STAGE_NOT_FOUND')) {
      return { status: 'stageNotFound', message: normalized.message }
    }
    if (normalized.is('INVALID_INPUT')) return readInvalid(error, normalized.message)
    return toFailure(normalized)
  }
}
