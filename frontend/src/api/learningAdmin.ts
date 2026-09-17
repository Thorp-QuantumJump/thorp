import axios from 'axios'
import { ApiClientError, apiClient, normalizeApiError, runCsrfProtectedMutation } from './client'
import type { ApiEnvelope, ApiErrorCode } from '@/types/api'
import type {
  AdminArticleStatus,
  AdminDraftBlock,
  AdminDraftExample,
  AdminDraftSource,
  AdminDraftTerm,
  AdminFieldViolation,
  AdminLearningArticle,
  AdminLearningArticleSummary,
  AdminLearningDraft,
  AdminPublicationRecord,
  CreateArticleRequest,
  PublishCheckFailure,
  PublishCheckLocation,
  PublishResult,
  SaveDraftRequest,
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
 * 고쳐서 다시 보낼 수 있는 거절(`draftChanged`·`checkFailed`)과 그렇지 않은 실패를 화면이 상태
 * 코드로 다시 판정하지 않게 한다.
 *
 * <p><b>스스로 다시 보내지 않는다.</b> 특히 `draftChanged`는 자동 병합도 자동 재전송도 하지 않는다
 * (R12). 덮어쓰기를 막으려고 만든 거절을 화면이 자동으로 뚫으면 막은 뜻이 사라진다.
 *
 * <p>CSRF 헤더는 `apiClient`가 쿠키에서 붙이며 상태 변경 요청은 `runCsrfProtectedMutation`을 지난다.
 * `401`은 `api/client.ts`의 인증 필요 리스너(014)가 함께 받는다.
 */

const BASE_PATH = '/admin/learning'

type LoadFailure = {
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

export type AdminArticleListResult =
  | { status: 'loaded'; articles: AdminLearningArticleSummary[] }
  | { status: 'denied' }
  | Unauthenticated
  | LoadFailure

export type AdminArticleResult =
  | { status: 'loaded'; article: AdminLearningArticle }
  | { status: 'notFound' }
  | Unauthenticated
  | LoadFailure

export type CreateArticleResult =
  | { status: 'created'; article: AdminLearningArticle }
  /** `409 LEARNING_ARTICLE_ID_TAKEN`. 운영자가 다른 식별자를 고르면 된다 */
  | { status: 'idTaken'; message: string }
  | { status: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | Unauthenticated
  | LoadFailure

/** 저장과 게시가 함께 쓰는 거절이다. 두 요청이 같은 조건으로 같은 코드를 돌려준다(계약 §5·§6). */
type DraftRejection =
  /** `409 LEARNING_DRAFT_CHANGED`. 화면은 입력을 남긴 채 새로 불러오기를 안내한다(R12) */
  | { status: 'draftChanged'; message: string; currentDraftVersion: number | null }
  | { status: 'termIdTaken'; message: string; termId: string | null; ownerArticleId: string | null }
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
  | DraftRejection
  | LoadFailure

export type UnpublishArticleResult =
  | { status: 'unpublished'; result: UnpublishResult }
  | { status: 'notFound' }
  | Unauthenticated
  | LoadFailure

const UNREADABLE_RESPONSE_MESSAGE = '응답을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function readStatus(value: unknown): AdminArticleStatus | null {
  return value === 'DRAFT' || value === 'PUBLISHED' ? value : null
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

function readSummary(value: unknown): AdminLearningArticleSummary | null {
  if (!isRecord(value) || !isString(value.articleId) || !isString(value.title)) return null
  if (typeof value.readingOrder !== 'number' || typeof value.draftVersion !== 'number') return null
  if (typeof value.hasUnpublishedChanges !== 'boolean') return null
  const status = readStatus(value.status)
  if (!status) return null

  // `null`은 "대응표를 확인할 수 없음"이라는 사실이다. `false`로 접지 않는다(원칙 VI).
  const needsUpdate = value.needsUpdate
  if (needsUpdate !== null && needsUpdate !== undefined && typeof needsUpdate !== 'boolean') {
    return null
  }

  const hasPublication = value.lastPublication !== null && value.lastPublication !== undefined
  const lastPublication = hasPublication ? readPublication(value.lastPublication) : null
  if (hasPublication && !lastPublication) return null

  return {
    articleId: value.articleId,
    title: value.title,
    readingOrder: value.readingOrder,
    status,
    draftVersion: value.draftVersion,
    hasUnpublishedChanges: value.hasUnpublishedChanges,
    needsUpdate: needsUpdate ?? null,
    lastPublication,
  }
}

/**
 * 초안 블록 하나를 읽는다.
 *
 * <p>공개 조회(`api/learning.ts`)와 달리 <b>모르는 `type`을 그대로 넘기지 않는다.</b> 이 값은 편집기가
 * 고쳐서 다시 저장하는 값이라, 읽지 못한 블록을 통과시키면 다음 저장에서 그 블록이 조용히 사라지거나
 * 400을 받는다. 서버도 알 수 없는 `type`을 저장에서 거절한다(data-model §4).
 */
function readBlock(value: unknown): AdminDraftBlock | null {
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

function readTerm(value: unknown): AdminDraftTerm | null {
  if (!isRecord(value) || !isString(value.termId) || !isString(value.label)) return null
  return isString(value.sentence)
    ? { termId: value.termId, label: value.label, sentence: value.sentence }
    : null
}

function readDraft(value: unknown): AdminLearningDraft | null {
  if (!isRecord(value) || !isString(value.title) || !isString(value.question)) return null
  if (typeof value.readingOrder !== 'number') return null
  if (!Array.isArray(value.blocks) || !Array.isArray(value.sources) || !Array.isArray(value.terms)) {
    return null
  }

  const blocks: AdminDraftBlock[] = []
  for (const item of value.blocks) {
    const block = readBlock(item)
    if (!block) return null
    blocks.push(block)
  }
  const sources: AdminDraftSource[] = []
  for (const item of value.sources) {
    const source = readSource(item)
    if (!source) return null
    sources.push(source)
  }
  const terms: AdminDraftTerm[] = []
  for (const item of value.terms) {
    const term = readTerm(item)
    if (!term) return null
    terms.push(term)
  }
  const example = readExample(value.example)
  if (example === 'invalid') return null

  return {
    title: value.title,
    question: value.question,
    readingOrder: value.readingOrder,
    blocks,
    example,
    sources,
    terms,
  }
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
  return {
    articleId: value.articleId,
    status,
    draftVersion: value.draftVersion,
    draft,
    publishedRevisionNo: publishedRevisionNo ?? null,
  }
}

function readPublishResult(value: unknown): PublishResult | null {
  if (!isRecord(value) || !isString(value.articleId)) return null
  const status = readStatus(value.status)
  if (!status || typeof value.revisionNo !== 'number') return null
  if (!isString(value.checkVersion) || !isString(value.sectorMapVersion)) return null
  if (!isString(value.publishedAt)) return null
  return {
    articleId: value.articleId,
    status,
    revisionNo: value.revisionNo,
    checkVersion: value.checkVersion,
    sectorMapVersion: value.sectorMapVersion,
    publishedAt: value.publishedAt,
  }
}

function readUnpublishResult(value: unknown): UnpublishResult | null {
  if (!isRecord(value) || !isString(value.articleId)) return null
  const status = readStatus(value.status)
  if (!status) return null
  const revisionNo = value.unpublishedRevisionNo
  if (revisionNo !== null && revisionNo !== undefined && typeof revisionNo !== 'number') return null
  return { articleId: value.articleId, status, unpublishedRevisionNo: revisionNo ?? null }
}

const CHECK_LOCATION_PARTS: readonly PublishCheckLocation['part'][] = [
  'title',
  'question',
  'example',
  'block',
  'term',
]

function readLocation(value: unknown): PublishCheckLocation | null {
  if (!isRecord(value)) return null
  const part = CHECK_LOCATION_PARTS.find((candidate) => candidate === value.part)
  if (!part) return null
  const location: PublishCheckLocation = { part }
  if (typeof value.index === 'number') location.index = value.index
  if (isString(value.termId)) location.termId = value.termId
  return location
}

/**
 * 걸린 검사 목록을 읽는다.
 *
 * <p><b>읽지 못한 항목만 빼지 않고 목록 전체를 비운다.</b> 일부만 보이면 운영자가 나머지를 고쳤다고
 * 믿고 다시 게시를 누른다. 그러면 같은 422를 다시 받으면서도 무엇이 빠졌는지 모른다. 목록이 비면
 * 화면은 항목 자리를 만들지 않고 서버 문장만 보인다.
 */
function readFailures(value: unknown): PublishCheckFailure[] {
  if (!Array.isArray(value)) return []
  const failures: PublishCheckFailure[] = []
  for (const item of value) {
    if (!isRecord(item) || !isString(item.check) || !isString(item.message)) return []
    const location = readLocation(item.location)
    if (!location) return []
    const failure: PublishCheckFailure = { check: item.check, location, message: item.message }
    if (isString(item.matched)) failure.matched = item.matched
    failures.push(failure)
  }
  return failures
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
      throw new ApiClientError('api', envelope.error.message, { code: envelope.error.code })
    }
    return envelope.data
  }
  return body as T
}

function unreadable(): LoadFailure {
  return { status: 'failed', kind: 'unexpected', code: null, message: UNREADABLE_RESPONSE_MESSAGE }
}

function toFailure(error: ApiClientError): LoadFailure {
  return { status: 'failed', kind: error.kind, code: error.code, message: error.message }
}

function statusOf(error: unknown): number | null {
  const source = error instanceof ApiClientError ? error.cause : error
  return axios.isAxiosError(source) ? (source.response?.status ?? null) : null
}

function articlePath(articleId: string, suffix = ''): string {
  return `${BASE_PATH}/articles/${encodeURIComponent(articleId)}${suffix}`
}

function readNumber(details: Record<string, unknown> | null, key: string): number | null {
  const value = details?.[key]
  return typeof value === 'number' ? value : null
}

function readText(details: Record<string, unknown> | null, key: string): string | null {
  const value = details?.[key]
  return isString(value) ? value : null
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
    if (!isRecord(body) || !Array.isArray(body.articles)) return unreadable()

    const articles: AdminLearningArticleSummary[] = []
    for (const item of body.articles) {
      const summary = readSummary(item)
      if (!summary) return unreadable()
      articles.push(summary)
    }
    return { status: 'loaded', articles }
  } catch (error) {
    const status = statusOf(error)
    if (status === 401) return { status: 'unauthenticated' }
    if (status === 404) return { status: 'denied' }
    return toFailure(normalizeApiError(error))
  }
}

/** 글을 만든다(계약 §3). 새 글은 `DRAFT`, `draftVersion: 0`이다. */
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
    if (normalized.is('INVALID_INPUT')) {
      return {
        status: 'invalid',
        message: normalized.message,
        fields: readFieldViolations(readDetails(error)?.fields),
      }
    }
    return toFailure(normalized)
  }
}

/** 초안 하나를 받아 온다(계약 §4). */
export async function fetchAdminArticle(
  articleId: string,
  signal?: AbortSignal,
): Promise<AdminArticleResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<AdminLearningArticle>>(articlePath(articleId), {
      signal,
    })
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
    if (normalized.is('INVALID_INPUT')) {
      return {
        status: 'invalid',
        message: normalized.message,
        fields: readFieldViolations(readDetails(error)?.fields),
      }
    }
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
