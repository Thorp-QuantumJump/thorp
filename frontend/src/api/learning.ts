import { ApiClientError, apiClient, normalizeApiError } from './client'
import type { ApiEnvelope, ApiErrorCode } from '@/types/api'
import type {
  ArticleNext,
  ArticleStage,
  CurrentArticleBlock,
  CurrentLearningArticle,
  Curriculum,
  CurriculumArticle,
  DiagramSeries,
  LearningArticle,
  LearningArticleSummary,
  LearningExample,
  LearningExampleSector,
  LearningSource,
  LegacyArticleBlock,
  LegacyLearningArticle,
  NoteSource,
  PreviousEditionArticle,
  QuizChoice,
  QuizQuestion,
  ReleaseLogEntry,
  Run,
  StageView,
} from '@/types/learning'

/**
 * 배우기 공개 조회(013 contracts/learning-public-api.md §1·§3)의 통로다.
 *
 * <p><b>빈 목록과 실패를 섞지 않는다.</b> 게시된 글이 없는 200과 목록을 받지 못한 실패는 사용자가
 * 할 수 있는 일이 다르다(013 SC-007). 받지 못했거나 계약과 다른 응답은 모두 `failed`이고, 빈 목록은
 * 받은 커리큘럼의 내용이다. 계약과 다른 모양을 빈 배열로 접지 않는다 — `api/sectors.ts`의
 * `SectorCatalogResult`가 같은 이유로 같은 구조를 쓴다.
 *
 * <p><b>예외를 던지지 않고 재시도하지 않는다.</b> 다시 시도는 사용자가 누를 때 다시 부른다(013 FR-015).
 * 오류 분류는 014의 `normalizeApiError`를 그대로 쓴다.
 */

type LoadFailure = {
  status: 'failed'
  kind: 'api' | 'network' | 'unexpected'
  /** 서버가 보낸 이름 그대로다. 아는 이름이 아니어도 버리지 않는다 */
  code: ApiErrorCode | null
  message: string
}

/**
 * 커리큘럼 조회 결과. 게시된 글이 없는 것도 `loaded`다(계약 §1 "게시된 글이 하나도 없어도 200").
 * 빈 목록과 실패는 화면이 `loaded`의 내용으로 가른다.
 */
export type CurriculumResult = { status: 'loaded'; curriculum: Curriculum } | LoadFailure

export type LearningArticleResult =
  | { status: 'loaded'; article: LearningArticle }
  /** 404 `LEARNING_ARTICLE_UNAVAILABLE`. 없는 글과 게시되지 않은 글을 서버가 가르지 않는다 */
  | { status: 'unavailable' }
  | LoadFailure

const UNREADABLE_RESPONSE_MESSAGE = '배우기 글을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 게시 시각을 뺀 글이다. 관리 미리 보기(관리 계약 §8)가 같은 모양에 게시 전이라 `publishedAt`이 `null`인
 * 응답을 주므로, 해석은 같은 함수로 하고 게시 시각만 따로 읽는다.
 *
 * 미리 보기는 예시 없는 V1 초안을 9월 판 모양에 `example: null`로 보낸다. 그래서 이 타입의 9월 판 예시는
 * 비어 있을 수 있다. 공개 글(`LearningArticle`)의 9월 판에는 늘 예시가 있다.
 */
export type LearningArticleContent =
  | (Omit<LegacyLearningArticle, 'publishedAt' | 'example'> & { example: LearningExample | null })
  | Omit<CurrentLearningArticle, 'publishedAt'>

export type ArticleContentOptions = {
  /**
   * 9월 판 모양의 `example: null`을 받는다. 관리 미리 보기만 켠다. 게시된 9월 판에 예시가 없으면 서버가 잘못
   * 보낸 것이라 공개 조회는 받지 않는다.
   */
  legacyExampleOptional?: boolean
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function readSummary(value: unknown): LearningArticleSummary | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isString(value.title) ||
    !isString(value.question) ||
    typeof value.readingOrder !== 'number'
  ) {
    return null
  }
  return {
    articleId: value.articleId,
    title: value.title,
    question: value.question,
    readingOrder: value.readingOrder,
  }
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number'
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(isString)
}

/** 배열의 항목을 모두 읽는다. 하나라도 읽지 못하면 배열 전체를 읽지 못한 것으로 둔다. */
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

/**
 * 9월 판 블록 하나를 읽는다(`PRE_RELEASE`·`PREVIOUS`, 계약 §3-1).
 *
 * <b>모르는 `type`은 읽지 못한 응답으로 다루지 않는다.</b> 서버가 새 블록 종류를 더해도 글 전체가
 * 실패로 바뀌지 않게 그대로 넘기고, 그리지 않는 판단은 `ArticleBody`가 한다. 아는 종류인데 필드가
 * 빠졌으면 계약 위반이므로 빈 글로 채우지 않고 응답 전체를 읽지 못한 것으로 둔다.
 */
function readLegacyBlock(value: unknown): LegacyArticleBlock | null {
  if (!isRecord(value) || !isString(value.type)) return null
  switch (value.type) {
    case 'heading':
    case 'paragraph':
    case 'hypothetical':
      return isString(value.text) ? { type: value.type, text: value.text } : null
    case 'term':
      return isString(value.termId) && isString(value.label) && isString(value.sentence)
        ? { type: 'term', termId: value.termId, label: value.label, sentence: value.sentence }
        : null
    default:
      return value as unknown as LegacyArticleBlock
  }
}

function readNoteSource(value: unknown): NoteSource | null {
  if (!isRecord(value) || !isString(value.label) || !isString(value.url)) return null
  return { label: value.label, url: value.url }
}

/**
 * 글자 조각 하나를 읽는다(data-model §5-2). 조각은 글자·용어 언급·출처 표시 가운데 하나다.
 *
 * 블록과 달리 모르는 조각은 통과시키지 않는다. 서버가 저장할 때 모르는 키를 거절하므로(§5-2) 여기서
 * 모르는 모양을 만나면 계약 위반이고, 문장 한가운데가 빈 채로 그려지면 뜻이 바뀐다.
 */
function readRun(value: unknown): Run | null {
  if (!isRecord(value)) return null
  if ('source' in value) {
    const source = readNoteSource(value.source)
    return source ? { source } : null
  }
  if ('term' in value) {
    return isString(value.term) &&
      isString(value.text) &&
      typeof value.owned === 'boolean' &&
      isStringArray(value.aliases)
      ? { term: value.term, text: value.text, owned: value.owned, aliases: value.aliases }
      : null
  }
  return isString(value.text) ? { text: value.text } : null
}

function readRuns(value: unknown): Run[] | null {
  return readAll(value, readRun)
}

function readRunLines(value: unknown): Run[][] | null {
  return readAll(value, readRuns)
}

function readDiagramSeries(value: unknown): DiagramSeries | null {
  if (!isRecord(value) || !isString(value.name)) return null
  if (!Array.isArray(value.values) || !value.values.every(isNumber)) return null
  return { name: value.name, values: value.values }
}

/**
 * 새 기준 블록 하나를 읽는다(`CURRENT`, 계약 §3-2, data-model §5-3).
 *
 * 9월 판과 같은 원칙이다. 모르는 `type`은 그대로 넘기고, 아는 종류의 필드가 틀리면 응답 전체를 읽지
 * 못한 것으로 둔다. 같은 `type` 이름이라도 9월 판 필드(`paragraph`의 `text` 등)만 있으면 틀린 것이다.
 */
function readCurrentBlock(value: unknown): CurrentArticleBlock | null {
  if (!isRecord(value) || !isString(value.type)) return null
  switch (value.type) {
    case 'keySummary': {
      const lines = readRunLines(value.lines)
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
      return isString(value.termId) &&
        isString(value.label) &&
        isStringArray(value.aliases) &&
        isString(value.meaning) &&
        isString(value.reading) &&
        isString(value.caveat)
        ? {
            type: 'term',
            termId: value.termId,
            label: value.label,
            aliases: value.aliases,
            meaning: value.meaning,
            reading: value.reading,
            caveat: value.caveat,
          }
        : null
    case 'misconception': {
      const wrong = readRuns(value.wrong)
      const correction = readRuns(value.correction)
      return wrong && correction ? { type: 'misconception', wrong, correction } : null
    }
    case 'list': {
      const items = readRunLines(value.items)
      return items && typeof value.ordered === 'boolean'
        ? { type: 'list', ordered: value.ordered, items }
        : null
    }
    case 'table': {
      const rows = readAll(value.rows, readRunLines)
      return rows && isString(value.caption) && isStringArray(value.columns)
        ? { type: 'table', caption: value.caption, columns: value.columns, rows }
        : null
    }
    case 'diagram': {
      const series = readAll(value.series, readDiagramSeries)
      // 모르는 종류는 null로 받아 그림만 빼고 설명 문장을 보인다. 모르는 블록 종류를 통과시키는 것과 같은
      // 원칙이다. 종류가 글자가 아니면 계약 위반이다.
      return series &&
        isString(value.kind) &&
        isString(value.title) &&
        isString(value.unit) &&
        isStringArray(value.categories) &&
        isString(value.description)
        ? {
            type: 'diagram',
            kind: value.kind === 'bar' || value.kind === 'line' ? value.kind : null,
            title: value.title,
            unit: value.unit,
            categories: value.categories,
            series,
            description: value.description,
          }
        : null
    }
    case 'institutionNote': {
      const content = readRuns(value.content)
      if (!content) return null
      if (value.asOf !== null && !isString(value.asOf)) return null
      const source = value.source === null ? null : readNoteSource(value.source)
      if (value.source !== null && !source) return null
      return { type: 'institutionNote', content, asOf: value.asOf, source }
    }
    default:
      return value as unknown as CurrentArticleBlock
  }
}

function readQuizChoice(value: unknown): QuizChoice | null {
  if (!isRecord(value) || !isString(value.choiceId) || !isString(value.text)) return null
  return { choiceId: value.choiceId, text: value.text }
}

function readQuizQuestion(value: unknown): QuizQuestion | null {
  if (
    !isRecord(value) ||
    !isString(value.questionId) ||
    !isString(value.prompt) ||
    !isString(value.correctChoiceId) ||
    !isString(value.explanation)
  ) {
    return null
  }
  const choices = readAll(value.choices, readQuizChoice)
  if (!choices) return null
  return {
    questionId: value.questionId,
    prompt: value.prompt,
    choices,
    correctChoiceId: value.correctChoiceId,
    explanation: value.explanation,
  }
}

function readStage(value: unknown): ArticleStage | null {
  if (
    !isRecord(value) ||
    !isString(value.stageKey) ||
    !isNumber(value.number) ||
    !isString(value.name)
  ) {
    return null
  }
  return { stageKey: value.stageKey, number: value.number, name: value.name }
}

function readNext(value: unknown): ArticleNext | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !(value.number === null || isString(value.number)) ||
    !isString(value.title)
  ) {
    return null
  }
  return { articleId: value.articleId, number: value.number, title: value.title }
}

function readExampleSector(value: unknown): LearningExampleSector | null {
  if (
    !isRecord(value) ||
    !isString(value.code) ||
    !(value.name === null || isString(value.name)) ||
    typeof value.weightTenths !== 'number'
  ) {
    return null
  }
  return { code: value.code, name: value.name, weightTenths: value.weightTenths }
}

function readExample(value: unknown): LearningExample | null {
  if (
    !isRecord(value) ||
    typeof value.available !== 'boolean' ||
    !Array.isArray(value.sectors) ||
    !Array.isArray(value.missingSectorCodes) ||
    !value.missingSectorCodes.every(isString)
  ) {
    return null
  }
  const reason = value.unavailableReason
  if (reason !== null && reason !== 'SECTOR_NOT_IN_MAP' && reason !== 'SECTOR_MAP_NOT_READY') {
    return null
  }
  const sectors: LearningExampleSector[] = []
  for (const item of value.sectors) {
    const sector = readExampleSector(item)
    if (!sector) return null
    sectors.push(sector)
  }
  return {
    available: value.available,
    sectors,
    missingSectorCodes: value.missingSectorCodes,
    unavailableReason: reason,
  }
}

function readSource(value: unknown): LearningSource | null {
  if (!isRecord(value) || !isString(value.label)) return null
  // 계약이 "url은 없을 수 있다"고 정했다. 키가 없는 것과 null을 같은 뜻으로 받는다.
  if (value.url !== undefined && value.url !== null && !isString(value.url)) return null
  return { label: value.label, url: value.url ?? null }
}

/**
 * 글 하나를 읽는다(계약 §3).
 *
 * <p><b>`edition`을 먼저 읽는다.</b> 같은 `type` 이름의 블록이 판마다 필드가 달라서(9월 판 `paragraph`의
 * `text`, 새 기준 `paragraph`의 `content`) 판을 모르고는 블록을 읽을 수 없다. `edition`이 없거나 세 값이
 * 아니면 읽지 못한 응답이다.
 *
 * <p><b>예시는 판마다 다르다.</b> 새 기준 글은 예시가 선택이라 `null`을 그대로 받고, 9월 판 글은 지금처럼
 * 객체여야 한다(data-model §9).
 */
export function readArticleContent(
  value: unknown,
  options: ArticleContentOptions = {},
): LearningArticleContent | null {
  const summary = readSummary(value)
  if (!summary || !isRecord(value)) return null
  const edition = value.edition
  if (edition !== 'CURRENT' && edition !== 'PRE_RELEASE' && edition !== 'PREVIOUS') return null

  const stage = readStage(value.stage)
  const sources = readAll(value.sources, readSource)
  const quiz = readAll(value.quiz, readQuizQuestion)
  const next = value.next === null ? null : readNext(value.next)
  if (!stage || !sources || !quiz || (value.next !== null && !next)) return null
  if (
    !(value.number === null || isString(value.number)) ||
    !isNumber(value.readingMinutes) ||
    !(value.stageArticleCount === null || isNumber(value.stageArticleCount)) ||
    typeof value.hasUnreleasedStages !== 'boolean'
  ) {
    return null
  }
  const common = {
    ...summary,
    stage,
    number: value.number,
    readingMinutes: value.readingMinutes,
    sources,
    next,
    stageArticleCount: value.stageArticleCount,
    hasUnreleasedStages: value.hasUnreleasedStages,
    quiz,
  }

  if (edition === 'CURRENT') {
    const blocks = readAll(value.blocks, readCurrentBlock)
    const example = value.example === null ? null : readExample(value.example)
    if (!blocks || (value.example !== null && !example)) return null
    if (!isNumber(value.minDwellSeconds)) return null
    return { ...common, edition, blocks, example, minDwellSeconds: value.minDwellSeconds }
  }

  const blocks = readAll(value.blocks, readLegacyBlock)
  const exampleMissing = value.example === null && options.legacyExampleOptional === true
  const example = exampleMissing ? null : readExample(value.example)
  if (!blocks || (!exampleMissing && !example) || value.minDwellSeconds !== null) return null
  return { ...common, edition, blocks, example, minDwellSeconds: null }
}

/** 공개 글 하나를 읽는다. 게시 시각까지 있어야 한다. */
function readArticle(value: unknown): LearningArticle | null {
  const content = readArticleContent(value)
  if (!content || !isRecord(value) || !isString(value.publishedAt)) return null
  if (content.edition === 'CURRENT') return { ...content, publishedAt: value.publishedAt }
  // 기본 해석은 9월 판의 `example: null`을 받지 않으므로 여기서는 늘 있다. 타입을 좁히려고 한 번 더 본다.
  return content.example
    ? { ...content, example: content.example, publishedAt: value.publishedAt }
    : null
}

function readCurriculumArticle(value: unknown): CurriculumArticle | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isString(value.number) ||
    !isString(value.title) ||
    !isString(value.question) ||
    !isNumber(value.readingMinutes) ||
    !isNumber(value.termCount) ||
    typeof value.hasExample !== 'boolean'
  ) {
    return null
  }
  return {
    articleId: value.articleId,
    number: value.number,
    title: value.title,
    question: value.question,
    readingMinutes: value.readingMinutes,
    termCount: value.termCount,
    hasExample: value.hasExample,
  }
}

function readPreviousEditionArticle(value: unknown): PreviousEditionArticle | null {
  if (
    !isRecord(value) ||
    !isString(value.articleId) ||
    !isString(value.title) ||
    !isString(value.question) ||
    !isNumber(value.readingMinutes)
  ) {
    return null
  }
  return {
    articleId: value.articleId,
    title: value.title,
    question: value.question,
    readingMinutes: value.readingMinutes,
  }
}

/**
 * 단계 하나를 읽는다. 모르는 `status`는 읽지 못한 응답이다. 블록과 달리 넘기지 않는 까닭은 상태를 모르면
 * '준비 중'으로 그릴지 글을 보일지 정할 수 없고, 잘못 고르면 공개하지 않은 단계를 공개된 것처럼 보이기
 * 때문이다.
 */
function readStageView(value: unknown): StageView | null {
  if (
    !isRecord(value) ||
    !isString(value.stageKey) ||
    !isNumber(value.number) ||
    !isString(value.name) ||
    !isString(value.levelLabel) ||
    !isString(value.summary) ||
    !(value.releasedAt === null || isString(value.releasedAt))
  ) {
    return null
  }
  const status = value.status
  if (status !== 'OPEN' && status !== 'PREPARING' && status !== 'REPREPARING') return null
  const articles = readAll(value.articles, readCurriculumArticle)
  const previousEditionArticles = readAll(value.previousEditionArticles, readPreviousEditionArticle)
  if (!articles || !previousEditionArticles) return null
  return {
    stageKey: value.stageKey,
    number: value.number,
    name: value.name,
    levelLabel: value.levelLabel,
    summary: value.summary,
    status,
    releasedAt: value.releasedAt,
    articles,
    previousEditionArticles,
  }
}

function readReleaseLogEntry(value: unknown): ReleaseLogEntry | null {
  if (
    !isRecord(value) ||
    !isString(value.entryKey) ||
    !isNumber(value.stageNumber) ||
    !isString(value.stageName) ||
    !isString(value.occurredAt) ||
    !isNumber(value.newCount) ||
    !isNumber(value.rewrittenCount) ||
    !isStringArray(value.rewrittenTitles) ||
    !(value.articleTitle === null || isString(value.articleTitle))
  ) {
    return null
  }
  const kind = value.kind
  if (kind !== 'STAGE_RELEASED' && kind !== 'ARTICLE_ADDED') return null
  return {
    entryKey: value.entryKey,
    kind,
    stageNumber: value.stageNumber,
    stageName: value.stageName,
    occurredAt: value.occurredAt,
    newCount: value.newCount,
    rewrittenCount: value.rewrittenCount,
    rewrittenTitles: value.rewrittenTitles,
    articleTitle: value.articleTitle,
  }
}

/**
 * 커리큘럼을 읽는다(계약 §1).
 *
 * <p><b>`preReleaseArticles`는 공개한 단계 수와 맞아야 한다.</b> 공개 전(`releasedStageCount = 0`)에만
 * 배열이고 공개한 뒤에는 null이다. 둘이 어긋나면 화면이 1판 목록과 여정 지도 가운데 무엇을 그릴지 정할 수
 * 없으므로 읽지 못한 응답으로 둔다.
 */
function readCurriculum(value: unknown): Curriculum | null {
  if (
    !isRecord(value) ||
    !isNumber(value.releasedStageCount) ||
    typeof value.allStagesReleased !== 'boolean'
  ) {
    return null
  }
  const stages = readAll(value.stages, readStageView)
  const notices = readAll(value.notices, readReleaseLogEntry)
  const changeLog = readAll(value.changeLog, readReleaseLogEntry)
  if (!stages || !notices || !changeLog) return null

  const preReleaseArticles =
    value.preReleaseArticles === null ? null : readAll(value.preReleaseArticles, readSummary)
  if (value.preReleaseArticles !== null && !preReleaseArticles) return null
  if ((value.releasedStageCount === 0) !== (preReleaseArticles !== null)) return null

  return {
    releasedStageCount: value.releasedStageCount,
    allStagesReleased: value.allStagesReleased,
    stages,
    preReleaseArticles,
    notices,
    changeLog,
  }
}

function unreadable(): LoadFailure {
  return { status: 'failed', kind: 'unexpected', code: null, message: UNREADABLE_RESPONSE_MESSAGE }
}

function toFailure(error: ApiClientError): LoadFailure {
  return { status: 'failed', kind: error.kind, code: error.code, message: error.message }
}

/** 봉투를 벗긴다. 실패 봉투가 200으로 오면 오류로 바꿔 던진다 — 아래 `catch`가 받는다. */
function unwrap<T>(body: ApiEnvelope<T>): T {
  if (!body.success) {
    throw new ApiClientError('api', body.error.message, { code: body.error.code })
  }
  return body.data
}

/** 커리큘럼을 한 번 받아 온다(계약 §1). */
export async function fetchCurriculum(signal?: AbortSignal): Promise<CurriculumResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<Curriculum>>('/learning/curriculum', {
      signal,
    })
    const curriculum = readCurriculum(unwrap(response.data))
    return curriculum ? { status: 'loaded', curriculum } : unreadable()
  } catch (error) {
    return toFailure(normalizeApiError(error))
  }
}

/** 게시된 글 하나를 받아 온다. */
export async function fetchLearningArticle(
  articleId: string,
  signal?: AbortSignal,
): Promise<LearningArticleResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<LearningArticle>>(
      `/learning/articles/${encodeURIComponent(articleId)}`,
      { signal },
    )
    const article = readArticle(unwrap(response.data))
    return article ? { status: 'loaded', article } : unreadable()
  } catch (error) {
    const normalized = normalizeApiError(error)
    if (normalized.kind === 'api' && normalized.is('LEARNING_ARTICLE_UNAVAILABLE')) {
      return { status: 'unavailable' }
    }
    return toFailure(normalized)
  }
}
