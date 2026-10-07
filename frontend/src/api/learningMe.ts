import {
  ApiClientError,
  apiClient,
  normalizeApiError,
  prepareCsrfToken,
  runCsrfProtectedMutation,
} from './client'
import type { ApiEnvelope, ApiErrorCode } from '@/types/api'
import type {
  AnswerResult,
  CompletionResult,
  ContinueReading,
  Gained,
  GainedBadge,
  GainedExp,
  LearnerArticleEntry,
  LearnerArticleRecord,
  LearnerArticleState,
  BadgeView,
  LearnerOverview,
  LearnerStageEntry,
  MasteredTerm,
  QuizSummary,
  RetryResult,
  SavedAnswer,
  StageProgress,
} from '@/types/learningMe'

/**
 * 나의 학습(013 contracts/learning-me-api.md)의 통로다.
 *
 * <p><b>예외를 던지지 않고 결과 합집합으로 돌려준다.</b> 화면이 가를 일이 실패마다 다르다. 401은 로그인이
 * 풀린 것이라 '기록되지 않았습니다'와 재로그인 안내를, `LEARNING_NOT_RECORDABLE`은 기록하지 않는 글을 보인다.
 * 다른 곳에서 글이나 기록이 바뀌어 같은 요청을 다시 보내도 같은 실패가 나는 경우(`stale`)와 문제가 바뀐 경우
 * (`questionNotFound`)는 '다시 시도' 대신 글을 다시 불러오게 한다. 그 밖은 다시 보낼 수 있는 한 가지 실패다.
 *
 * <p><b>조용히 재시도하지 않는다.</b> 다시 시도는 사용자가 누를 때 다시 부른다(계약 §0, SC-010). 상태 변경은
 * `runCsrfProtectedMutation`으로 감싸 CSRF 토큰이 어긋났을 때 토큰만 새로 받아 둔다.
 *
 * <p>비로그인 화면은 이 파일의 어떤 함수도 부르지 않는다(FR-020).
 */

type MeFailure =
  /** 401 `AUTHENTICATION_REQUIRED`. 로그인이 풀렸다 */
  | { status: 'unauthenticated' }
  /** 409 `LEARNING_NOT_RECORDABLE`. 공개된 글이 아니거나 첫 단계 공개 전이다 */
  | { status: 'notRecordable' }
  /**
   * 다른 곳에서 글이나 기록이 바뀌었다. 404 `LEARNING_ARTICLE_UNAVAILABLE`(게시 취소), 409
   * `LEARNING_COMPLETION_REQUIRED`(다른 탭에서 학습 초기화), 409 `LEARNING_RETRY_NOT_AVAILABLE`(다른 탭에서 이미
   * 익힘)이다. 같은 요청을 다시 보내도 같은 실패라 '다시 시도'를 주지 않는다(계약 §0·§5·§6)
   */
  | { status: 'stale' }
  | {
      status: 'failed'
      kind: 'api' | 'network' | 'unexpected'
      /** 서버가 보낸 이름 그대로다. 아는 이름이 아니어도 버리지 않는다 */
      code: ApiErrorCode | null
      message: string
    }

export type LearningMeResult<T> = { status: 'ok'; data: T } | MeFailure

/** 첫 답·다시 풀기는 공개 판에 그 문제나 보기가 없다는 실패를 따로 가른다(계약 §5·§6). */
export type LearningAnswerResult<T> = LearningMeResult<T> | { status: 'questionNotFound' }

const UNREADABLE_RESPONSE_MESSAGE = '학습 기록을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

function articlePath(articleId: string): string {
  return `/learning/me/articles/${encodeURIComponent(articleId)}`
}

function unwrap<T>(body: ApiEnvelope<T>): T {
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, { code: body.error.code })
  }
  return body.data
}

function unreadable(): MeFailure {
  return { status: 'failed', kind: 'unexpected', code: null, message: UNREADABLE_RESPONSE_MESSAGE }
}

function toFailure(error: unknown): MeFailure {
  const normalized = normalizeApiError(error)
  if (normalized.kind === 'api' && normalized.is('AUTHENTICATION_REQUIRED')) {
    return { status: 'unauthenticated' }
  }
  if (normalized.kind === 'api' && normalized.is('LEARNING_NOT_RECORDABLE')) {
    return { status: 'notRecordable' }
  }
  if (
    normalized.kind === 'api' &&
    (normalized.is('LEARNING_ARTICLE_UNAVAILABLE') ||
      normalized.is('LEARNING_COMPLETION_REQUIRED') ||
      normalized.is('LEARNING_RETRY_NOT_AVAILABLE'))
  ) {
    return { status: 'stale' }
  }
  return {
    status: 'failed',
    kind: normalized.kind,
    code: normalized.code,
    message: normalized.message,
  }
}

function toAnswerFailure(error: unknown): LearningAnswerResult<never> {
  const normalized = normalizeApiError(error)
  if (normalized.kind === 'api' && normalized.is('LEARNING_QUESTION_NOT_FOUND')) {
    return { status: 'questionNotFound' }
  }
  return toFailure(normalized)
}

// ── 응답 읽기 ────────────────────────────────────────────────────────────────
// 계약과 다른 모양은 빈 값으로 채우지 않고 읽지 못한 응답으로 둔다. 빈 기록을 '안 읽음'으로 그리면 거짓이다(FR-020).

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean'
}

function isNullableString(value: unknown): value is string | null {
  return value === null || isString(value)
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || isNumber(value)
}

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

function readState(value: unknown): LearnerArticleState | null {
  return value === 'UNREAD' || value === 'READING' || value === 'COMPLETED' ? value : null
}

function readContinueReading(value: unknown): ContinueReading | null {
  if (
    !isRecord(value) ||
    (value.kind !== 'RESUME' && value.kind !== 'START' && value.kind !== 'ALL_DONE') ||
    !isNullableString(value.articleId) ||
    !isNullableString(value.number) ||
    !isNullableString(value.title) ||
    !isNullableNumber(value.percent) ||
    !isBoolean(value.moreStagesPreparing)
  ) {
    return null
  }
  // 이어서 읽을 글이 있다는 값인데 글이 없으면 연결을 만들 수 없다.
  if (value.kind !== 'ALL_DONE' && (value.articleId === null || value.title === null)) return null
  return {
    kind: value.kind,
    articleId: value.articleId,
    number: value.number,
    title: value.title,
    percent: value.percent,
    moreStagesPreparing: value.moreStagesPreparing,
  }
}

function readStageProgress(value: unknown): StageProgress | null {
  if (
    !isRecord(value) ||
    !isNumber(value.stageNumber) ||
    !isNumber(value.completedCount) ||
    !isNumber(value.publishedCount) ||
    !isBoolean(value.stageCompleted)
  ) {
    return null
  }
  return {
    stageNumber: value.stageNumber,
    completedCount: value.completedCount,
    publishedCount: value.publishedCount,
    stageCompleted: value.stageCompleted,
  }
}

function readQuiz(value: unknown): QuizSummary | null {
  if (
    !isRecord(value) ||
    !isNumber(value.total) ||
    !isNumber(value.answered) ||
    !isNumber(value.correct) ||
    !isBoolean(value.allAnswered) ||
    !isBoolean(value.mastered) ||
    (value.masteredVia !== null &&
      value.masteredVia !== 'FIRST_ANSWER' &&
      value.masteredVia !== 'RETRY') ||
    !isBoolean(value.retryAvailable)
  ) {
    return null
  }
  return {
    total: value.total,
    answered: value.answered,
    correct: value.correct,
    allAnswered: value.allAnswered,
    mastered: value.mastered,
    masteredVia: value.masteredVia,
    retryAvailable: value.retryAvailable,
  }
}

function readSavedAnswer(value: unknown): SavedAnswer | null {
  if (
    !isRecord(value) ||
    !isString(value.questionId) ||
    !isString(value.choiceId) ||
    !isBoolean(value.correct) ||
    !isString(value.answeredAt)
  ) {
    return null
  }
  return {
    questionId: value.questionId,
    choiceId: value.choiceId,
    correct: value.correct,
    answeredAt: value.answeredAt,
  }
}

function readGainedExp(value: unknown): GainedExp | null {
  if (!isRecord(value) || !isString(value.kind) || !isNumber(value.points)) return null
  if (value.stageNumber !== undefined && !isNumber(value.stageNumber)) return null
  return value.stageNumber === undefined
    ? { kind: value.kind, points: value.points }
    : { kind: value.kind, points: value.points, stageNumber: value.stageNumber }
}

function readGainedBadge(value: unknown): GainedBadge | null {
  if (
    !isRecord(value) ||
    !isString(value.badgeKey) ||
    !isString(value.family) ||
    !isString(value.name)
  ) {
    return null
  }
  return { badgeKey: value.badgeKey, family: value.family, name: value.name }
}

function readGained(value: unknown): Gained | null {
  if (
    !isRecord(value) ||
    !isNumber(value.expTotal) ||
    !isNumber(value.levelBefore) ||
    !isNumber(value.levelAfter)
  ) {
    return null
  }
  const exp = readAll(value.exp, readGainedExp)
  const badges = readAll(value.badges, readGainedBadge)
  if (!exp || !badges) return null
  return {
    exp,
    expTotal: value.expTotal,
    badges,
    levelBefore: value.levelBefore,
    levelAfter: value.levelAfter,
  }
}

function readArticleRecord(value: unknown): LearnerArticleRecord | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isNumber(value.percent) ||
    !isNullableString(value.completedAt)
  ) {
    return null
  }
  const state = readState(value.state)
  const answers = readAll(value.answers, readSavedAnswer)
  const quiz = readQuiz(value.quiz)
  const stageProgress = readStageProgress(value.stageProgress)
  const continueReading = readContinueReading(value.continueReading)
  if (!state || !answers || !quiz || !stageProgress || !continueReading) return null
  return {
    articleId: value.articleId,
    state,
    percent: value.percent,
    completedAt: value.completedAt,
    answers,
    quiz,
    stageProgress,
    continueReading,
  }
}

function readCompletion(value: unknown): CompletionResult | null {
  if (!isRecord(value) || !isString(value.completedAt) || !isBoolean(value.alreadyCompleted)) {
    return null
  }
  const stageProgress = readStageProgress(value.stageProgress)
  const continueReading = readContinueReading(value.continueReading)
  const gained = readGained(value.gained)
  if (!stageProgress || !continueReading || !gained) return null
  return {
    completedAt: value.completedAt,
    alreadyCompleted: value.alreadyCompleted,
    stageProgress,
    continueReading,
    gained,
  }
}

function readRetry(value: unknown): RetryResult | null {
  if (
    !isRecord(value) ||
    !isString(value.questionId) ||
    !isString(value.choiceId) ||
    !isBoolean(value.correct) ||
    !isString(value.correctChoiceId) ||
    !isString(value.explanation)
  ) {
    return null
  }
  const quiz = readQuiz(value.quiz)
  const gained = readGained(value.gained)
  if (!quiz || !gained) return null
  return {
    questionId: value.questionId,
    choiceId: value.choiceId,
    correct: value.correct,
    correctChoiceId: value.correctChoiceId,
    explanation: value.explanation,
    quiz,
    gained,
  }
}

function readAnswer(value: unknown): AnswerResult | null {
  const base = readRetry(value)
  if (!base || !isRecord(value) || !isBoolean(value.alreadyAnswered)) return null
  return { ...base, alreadyAnswered: value.alreadyAnswered }
}

function readArticleEntry(value: unknown): LearnerArticleEntry | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isNumber(value.percent) ||
    !isNullableString(value.completedAt) ||
    !isNumber(value.termCount) ||
    !isNumber(value.masteredTermCount)
  ) {
    return null
  }
  const state = readState(value.state)
  if (!state) return null
  return {
    articleId: value.articleId,
    state,
    percent: value.percent,
    completedAt: value.completedAt,
    termCount: value.termCount,
    masteredTermCount: value.masteredTermCount,
  }
}

function readStageEntry(value: unknown): LearnerStageEntry | null {
  if (
    !isRecord(value) ||
    !isString(value.stageKey) ||
    !isNumber(value.number) ||
    !isString(value.status) ||
    !isNullableNumber(value.completedCount) ||
    !isNullableNumber(value.publishedCount) ||
    !isBoolean(value.completed)
  ) {
    return null
  }
  return {
    stageKey: value.stageKey,
    number: value.number,
    status: value.status,
    completedCount: value.completedCount,
    publishedCount: value.publishedCount,
    completed: value.completed,
  }
}

function readMasteredTerm(value: unknown): MasteredTerm | null {
  if (
    !isRecord(value) ||
    !isString(value.termId) ||
    !isString(value.label) ||
    !isString(value.articleId) ||
    !isNullableString(value.articleNumber)
  ) {
    return null
  }
  return {
    termId: value.termId,
    label: value.label,
    articleId: value.articleId,
    articleNumber: value.articleNumber,
  }
}

function readBadge(value: unknown): BadgeView | null {
  if (
    !isRecord(value) ||
    !isString(value.badgeKey) ||
    !isString(value.family) ||
    !isString(value.name) ||
    !isString(value.criterion) ||
    !isString(value.status) ||
    !isNullableString(value.earnedAt) ||
    !isBoolean(value.isNew) ||
    !isNullableString(value.preparingReason) ||
    !isNullableNumber(value.stageNumber)
  ) {
    return null
  }
  let progress: BadgeView['progress'] = null
  if (value.progress !== null) {
    if (
      !isRecord(value.progress) ||
      !isNumber(value.progress.current) ||
      !isNumber(value.progress.target)
    ) {
      return null
    }
    progress = { current: value.progress.current, target: value.progress.target }
  }
  return {
    badgeKey: value.badgeKey,
    family: value.family,
    name: value.name,
    criterion: value.criterion,
    status: value.status,
    earnedAt: value.earnedAt,
    isNew: value.isNew,
    progress,
    preparingReason: value.preparingReason,
    stageNumber: value.stageNumber,
  }
}

function readOverview(value: unknown): LearnerOverview | null {
  if (!isRecord(value) || !isRecord(value.level) || !isRecord(value.counts)) return null
  const { level, counts } = value
  if (
    !isNumber(level.current) ||
    !isNumber(level.exp) ||
    !isNumber(level.currentThreshold) ||
    !isNumber(level.nextThreshold) ||
    !isNumber(level.remainingToNext) ||
    !isNullableString(level.reachedAt) ||
    !isBoolean(level.isNew) ||
    !isNumber(level.visibleMax) ||
    !isNumber(level.unreachableFrom) ||
    !isString(level.unreachableReason) ||
    !isNumber(counts.completedArticles) ||
    !isNumber(counts.masteredTerms) ||
    !isNumber(counts.badges) ||
    !isNumber(counts.publishedArticles) ||
    !isNumber(counts.publishedTerms)
  ) {
    return null
  }
  const milestones = readAll(level.milestones, (item) =>
    isRecord(item) && isNumber(item.level) && isNumber(item.threshold)
      ? { level: item.level, threshold: item.threshold }
      : null,
  )
  const continueReading = readContinueReading(value.continueReading)
  const articles = readAll(value.articles, readArticleEntry)
  const stages = readAll(value.stages, readStageEntry)
  const masteredTerms = readAll(value.masteredTerms, readMasteredTerm)
  const badges = readAll(value.badges, readBadge)
  const gained = readGained(value.gained)
  if (!milestones || !continueReading || !articles || !stages || !masteredTerms || !badges || !gained) {
    return null
  }
  return {
    level: {
      current: level.current,
      exp: level.exp,
      currentThreshold: level.currentThreshold,
      nextThreshold: level.nextThreshold,
      remainingToNext: level.remainingToNext,
      reachedAt: level.reachedAt,
      isNew: level.isNew,
      visibleMax: level.visibleMax,
      unreachableFrom: level.unreachableFrom,
      unreachableReason: level.unreachableReason,
      milestones,
    },
    counts: {
      completedArticles: counts.completedArticles,
      masteredTerms: counts.masteredTerms,
      badges: counts.badges,
      publishedArticles: counts.publishedArticles,
      publishedTerms: counts.publishedTerms,
    },
    continueReading,
    articles,
    stages,
    masteredTerms,
    badges,
    gained,
  }
}

// ── 조회 ─────────────────────────────────────────────────────────────────────

async function getRecord<T>(
  path: string,
  read: (value: unknown) => T | null,
  signal?: AbortSignal,
): Promise<LearningMeResult<T>> {
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>(path, { signal })
    const data = read(unwrap(response.data))
    return data ? { status: 'ok', data } : unreadable()
  } catch (error) {
    return toFailure(error)
  }
}

/** 나의 학습 전체(계약 §1). 요청마다 정산한다. 첫 단계 공개 전에는 `notRecordable`이다. */
export function fetchLearningMe(signal?: AbortSignal): Promise<LearningMeResult<LearnerOverview>> {
  return getRecord('/learning/me', readOverview, signal)
}

/** 글 하나의 기록(계약 §2). 글 화면이 완료 카드와 확인 문제를 그릴 때 쓴다. 정산하지 않는다. */
export function fetchLearnerArticle(
  articleId: string,
  signal?: AbortSignal,
): Promise<LearningMeResult<LearnerArticleRecord>> {
  return getRecord(articlePath(articleId), readArticleRecord, signal)
}

// ── 상태 변경 ────────────────────────────────────────────────────────────────

async function mutate<T>(
  request: () => Promise<{ data: ApiEnvelope<unknown> }>,
  read: (value: unknown) => T | null,
): Promise<T | null> {
  const response = await runCsrfProtectedMutation(request)
  return read(unwrap(response.data))
}

/** 완료(계약 §4). 화면이 본문 끝과 최소 머문 시간이 함께 참이 되는 첫 순간에 한 번 보낸다. */
export async function completeArticle(
  articleId: string,
): Promise<LearningMeResult<CompletionResult>> {
  try {
    const data = await mutate(
      () => apiClient.post<ApiEnvelope<unknown>>(`${articlePath(articleId)}/completion`),
      readCompletion,
    )
    return data ? { status: 'ok', data } : unreadable()
  } catch (error) {
    return toFailure(error)
  }
}

/** 첫 답(계약 §5). 이미 답한 문제면 저장된 첫 답이 `alreadyAnswered`로 온다. */
export async function answerQuestion(
  articleId: string,
  questionId: string,
  choiceId: string,
): Promise<LearningAnswerResult<AnswerResult>> {
  try {
    const data = await mutate(
      () =>
        apiClient.post<ApiEnvelope<unknown>>(`${articlePath(articleId)}/answers`, {
          questionId,
          choiceId,
        }),
      readAnswer,
    )
    return data ? { status: 'ok', data } : unreadable()
  } catch (error) {
    return toAnswerFailure(error)
  }
}

/** 다시 풀기(계약 §6). 답을 저장하지 않고, 맞히면 익힘만 준다. */
export async function retryAnswer(
  articleId: string,
  questionId: string,
  choiceId: string,
): Promise<LearningAnswerResult<RetryResult>> {
  try {
    const data = await mutate(
      () =>
        apiClient.post<ApiEnvelope<unknown>>(`${articlePath(articleId)}/retry-answers`, {
          questionId,
          choiceId,
        }),
      readRetry,
    )
    return data ? { status: 'ok', data } : unreadable()
  } catch (error) {
    return toAnswerFailure(error)
  }
}

/** 학습 초기화(계약 §7). 회원정보 화면이 확인을 받은 뒤 부른다. */
export async function resetLearning(): Promise<LearningMeResult<null>> {
  try {
    await runCsrfProtectedMutation(() => apiClient.delete('/learning/me'))
    return { status: 'ok', data: null }
  } catch (error) {
    return toFailure(error)
  }
}

/**
 * 화면 안 이동으로 글을 떠날 때의 읽은 위치(계약 §3). 화면은 결과를 알리지 않는다. 떠나는 순간이라 보일 자리가
 * 없고, 이 기록이 실패해도 완료에는 영향이 없다(FR-020). 그래도 다른 통로처럼 던지지 않고 결과를 돌려준다.
 */
export async function recordPosition(
  articleId: string,
  percent: number,
): Promise<LearningMeResult<null>> {
  try {
    await runCsrfProtectedMutation(() =>
      apiClient.put(`${articlePath(articleId)}/position`, { percent }),
    )
    return { status: 'ok', data: null }
  } catch (error) {
    return toFailure(error)
  }
}

/**
 * 탭을 숨기거나 닫을 때 쓰는 keepalive 요청이 실을 CSRF 쿠키를 미리 받아 둔다. 떠나는 순간에는 토큰을 받을
 * 시간이 없다. 실패해도 위치 기록만 빠지므로 알리지 않는다.
 */
export function prepareLeaveRecording(): void {
  void prepareCsrfToken().catch(() => {})
}

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return decodeURIComponent(rest.join('='))
  }
  return null
}

/**
 * 탭을 숨기거나 닫을 때의 읽은 위치(계약 §3, research R32).
 *
 * <p><b>`apiClient`를 쓰지 않는다.</b> 페이지가 사라지는 중에도 요청이 끝까지 가야 하므로 `fetch`의
 * `keepalive`를 쓴다. `fetch`는 `apiClient`의 기준 주소와 헤더를 물려받지 않으므로 같은 기준 주소를 앞에 붙이고
 * `Content-Type`과 쿠키 `XSRF-TOKEN`의 `X-XSRF-TOKEN` 헤더를 직접 싣는다. 빠뜨리면 요청이 앱 정적 서버로
 * 가거나 CSRF에 걸려 조용히 사라진다. `navigator.sendBeacon`은 헤더를 실을 수 없어 쓰지 않는다.
 *
 * <p>결과를 돌려주지 않는다. 응답이 올 때는 이미 페이지가 사라졌을 수 있어 받을 쪽이 없다.
 */
export function sendPositionOnLeave(articleId: string, percent: number): void {
  const base = apiClient.defaults.baseURL ?? '/api'
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = readCookie('XSRF-TOKEN')
  if (token) headers['X-XSRF-TOKEN'] = token
  try {
    void fetch(`${base}${articlePath(articleId)}/position`, {
      method: 'PUT',
      keepalive: true,
      credentials: 'include',
      headers,
      body: JSON.stringify({ percent }),
    }).catch(() => {})
  } catch {
    // fetch가 없거나 요청을 만들지 못해도 떠나는 사용자를 막지 않는다.
  }
}
