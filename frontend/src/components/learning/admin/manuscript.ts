import type {
  AdminDraftBlockV1,
  AdminDraftBlockV2,
  AdminDraftExample,
  AdminDraftSource,
  AdminDraftTermV1,
  AdminDraftTermV2,
  DraftQuizChoice,
  DraftQuizQuestion,
  DraftSourceRef,
  EditableDraft,
  ManuscriptFile,
} from '@/types/learningAdmin'
import { toServerDraft } from './editableDraft'

/**
 * 원고 파일을 읽고 쓴다(013 research R26, `docs/learning-content/README.md`).
 *
 * <p>원고는 편집기 상태를 그대로 쓴 JSON이다. 모양은 초안 저장 요청에서 `expectedDraftVersion`을 뺀 것이고,
 * 글자 조각 필드만 편집 문법 문자열이다. 다른 글의 원고를 잘못 넣지 않도록 `articleId`를 함께 쓴다.
 *
 * <p><b>틀린 원고는 위치와 함께 통째로 거절한다.</b> 일부만 받아들이면 편집 상태가 원고와 서버 사이 어딘가가
 * 되어 무엇이 들어갔는지 알 수 없다. 모르는 키도 거절한다 — 오타 난 키(`qiuz`)를 버리면 그 내용이 말없이
 * 사라진다(원칙 VI). 길이·개수 판정은 서버가 저장에서 한다.
 *
 * <p><b>지금 초안에 없는 문제·보기 식별자는 `null`로 바꾼다.</b> 저장 API는 지어 낸 식별자를 400으로
 * 거절하므로, 그런 식별자는 새 문제·보기로 저장하게 한다(data-model §6-1). 공개된 글의 원고는 내보낸 그대로
 * 가져오면 식별자가 같아 사용자 기록이 이어진다.
 */

export type ManuscriptIssue = {
  /** 원고 안 위치. 예: `blocks[3].content`, `quiz[0].choices[1]` */
  where: string
  /** 편집 문법 오류일 때만 있다. 그 칸 안의 줄·열이다 */
  line?: number
  column?: number
  message: string
}

/**
 * 가져온 원고의 문제·보기 식별자 집계. 저장하면 `null`인 문제·보기가 새 식별자를 받는다. 공개된 글이면 새
 * 문제는 이미 답한 사용자에게도 답하지 않은 문제로 보이므로(data-model §6-3) 화면이 이 수를 알린다.
 */
export type ManuscriptIdSummary = {
  /** 식별자가 `null`인 문제 수(원고에서 `null`이었거나 지금 초안에 없어 바뀐 것) */
  newQuestions: number
  /** 식별자가 `null`인 보기 수 */
  newChoices: number
  /** 원고에 적혀 있었지만 지금 초안에 없어 `null`로 바꾼 식별자 수 */
  replaced: number
}

export type ManuscriptReadResult =
  | { ok: true; draft: EditableDraft; ids: ManuscriptIdSummary }
  | { ok: false; issues: ManuscriptIssue[] }

/** 지금 서버 초안이 가진 식별자. 원고의 식별자 가운데 여기 없는 것은 `null`이 된다. */
export type KnownQuizIds = { questionIds: ReadonlySet<string>; choiceIds: ReadonlySet<string> }

export function knownQuizIds(quiz: readonly DraftQuizQuestion[]): KnownQuizIds {
  return {
    questionIds: new Set(quiz.flatMap((question) => question.questionId ?? [])),
    choiceIds: new Set(
      quiz.flatMap((question) => question.choices.flatMap((choice) => choice.choiceId ?? [])),
    ),
  }
}

/** 지금 편집 상태를 원고 JSON으로 쓴다. 저장소에서 읽기 쉽게 두 칸 들여쓰고 끝에 줄바꿈을 둔다. */
export function writeManuscript(articleId: string, draft: EditableDraft): string {
  const file: ManuscriptFile = { articleId, ...draft }
  return `${JSON.stringify(file, null, 2)}\n`
}

class ShapeError extends Error {
  constructor(
    readonly where: string,
    message: string,
  ) {
    super(message)
  }
}

type Json = Record<string, unknown>

function record(value: unknown, where: string, keys: readonly string[]): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ShapeError(where, '객체여야 합니다.')
  }
  const unknown = Object.keys(value).filter((key) => !keys.includes(key))
  if (unknown.length > 0) {
    throw new ShapeError(where, `모르는 키가 있습니다: ${unknown.join(', ')}`)
  }
  return value as Json
}

function text(value: unknown, where: string): string {
  if (typeof value !== 'string') throw new ShapeError(where, '문자열이어야 합니다.')
  return value
}

function nullableText(value: unknown, where: string): string | null {
  return value === null ? null : text(value, where)
}

function number(value: unknown, where: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new ShapeError(where, '숫자여야 합니다.')
  }
  return value
}

function bool(value: unknown, where: string): boolean {
  if (typeof value !== 'boolean') throw new ShapeError(where, 'true 또는 false여야 합니다.')
  return value
}

function list<T>(value: unknown, where: string, read: (item: unknown, where: string) => T): T[] {
  if (!Array.isArray(value)) throw new ShapeError(where, '배열이어야 합니다.')
  return value.map((item, index) => read(item, `${where}[${index}]`))
}

function sourceRef(value: unknown, where: string): DraftSourceRef {
  const item = record(value, where, ['label', 'url'])
  return { label: text(item.label, `${where}.label`), url: text(item.url, `${where}.url`) }
}

function example(value: unknown, where: string): AdminDraftExample | null {
  if (value === null) return null
  const item = record(value, where, ['sectors'])
  return {
    sectors: list(item.sectors, `${where}.sectors`, (sector, at) => {
      const row = record(sector, at, ['code', 'weightTenths'])
      return {
        code: text(row.code, `${at}.code`),
        weightTenths: number(row.weightTenths, `${at}.weightTenths`),
      }
    }),
  }
}

function source(value: unknown, where: string): AdminDraftSource {
  const item = record(value, where, ['label', 'url'])
  return { label: text(item.label, `${where}.label`), url: nullableText(item.url, `${where}.url`) }
}

function blockV1(value: unknown, where: string): AdminDraftBlockV1 {
  const type = record(value, where, ['type', 'text', 'termId']).type
  switch (type) {
    case 'heading':
    case 'paragraph':
    case 'hypothetical': {
      const item = record(value, where, ['type', 'text'])
      return { type, text: text(item.text, `${where}.text`) }
    }
    case 'term': {
      const item = record(value, where, ['type', 'termId'])
      return { type, termId: text(item.termId, `${where}.termId`) }
    }
    default:
      throw new ShapeError(
        `${where}.type`,
        'V1 블록 종류(heading·paragraph·term·hypothetical)가 아닙니다.',
      )
  }
}

/** V2 블록. 글자 조각 필드는 편집 문법 문자열로 읽고, 문법은 다음 단계(`toServerDraft`)가 본다. */
function blockV2(value: unknown, where: string): AdminDraftBlockV2<string> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ShapeError(where, '객체여야 합니다.')
  }
  const type = (value as Json).type
  const at = (key: string) => `${where}.${key}`
  switch (type) {
    case 'keySummary': {
      const item = record(value, where, ['type', 'lines'])
      return { type, lines: list(item.lines, at('lines'), text) }
    }
    case 'heading': {
      const item = record(value, where, ['type', 'text'])
      return { type, text: text(item.text, at('text')) }
    }
    case 'paragraph':
    case 'hypothetical': {
      const item = record(value, where, ['type', 'content'])
      return { type, content: text(item.content, at('content')) }
    }
    case 'term': {
      const item = record(value, where, ['type', 'termId'])
      return { type, termId: text(item.termId, at('termId')) }
    }
    case 'misconception': {
      const item = record(value, where, ['type', 'wrong', 'correction'])
      return {
        type,
        wrong: text(item.wrong, at('wrong')),
        correction: text(item.correction, at('correction')),
      }
    }
    case 'list': {
      const item = record(value, where, ['type', 'ordered', 'items'])
      return {
        type,
        ordered: bool(item.ordered, at('ordered')),
        items: list(item.items, at('items'), text),
      }
    }
    case 'table': {
      const item = record(value, where, ['type', 'caption', 'columns', 'rows'])
      return {
        type,
        caption: text(item.caption, at('caption')),
        columns: list(item.columns, at('columns'), text),
        rows: list(item.rows, at('rows'), (row, rowAt) => list(row, rowAt, text)),
      }
    }
    case 'diagram': {
      const item = record(value, where, [
        'type',
        'kind',
        'title',
        'unit',
        'categories',
        'series',
        'description',
      ])
      if (item.kind !== 'bar' && item.kind !== 'line') {
        throw new ShapeError(at('kind'), 'bar 또는 line이어야 합니다.')
      }
      return {
        type,
        kind: item.kind,
        title: text(item.title, at('title')),
        unit: text(item.unit, at('unit')),
        categories: list(item.categories, at('categories'), text),
        series: list(item.series, at('series'), (series, seriesAt) => {
          const row = record(series, seriesAt, ['name', 'values'])
          return {
            name: text(row.name, `${seriesAt}.name`),
            values: list(row.values, `${seriesAt}.values`, number),
          }
        }),
        description: text(item.description, at('description')),
      }
    }
    case 'institutionNote': {
      const item = record(value, where, ['type', 'content', 'asOf', 'source'])
      return {
        type,
        content: text(item.content, at('content')),
        asOf: nullableText(item.asOf, at('asOf')),
        source: item.source === null ? null : sourceRef(item.source, at('source')),
      }
    }
    default:
      throw new ShapeError(at('type'), '알 수 없는 블록 종류입니다.')
  }
}

function termV1(value: unknown, where: string): AdminDraftTermV1 {
  const item = record(value, where, ['termId', 'label', 'sentence'])
  return {
    termId: text(item.termId, `${where}.termId`),
    label: text(item.label, `${where}.label`),
    sentence: text(item.sentence, `${where}.sentence`),
  }
}

function termV2(value: unknown, where: string): AdminDraftTermV2 {
  const item = record(value, where, [
    'termId',
    'label',
    'aliases',
    'meaning',
    'reading',
    'caveat',
    'transferFrom',
  ])
  return {
    termId: text(item.termId, `${where}.termId`),
    label: text(item.label, `${where}.label`),
    aliases: list(item.aliases, `${where}.aliases`, text),
    meaning: text(item.meaning, `${where}.meaning`),
    reading: text(item.reading, `${where}.reading`),
    caveat: text(item.caveat, `${where}.caveat`),
    transferFrom:
      item.transferFrom === undefined
        ? null
        : nullableText(item.transferFrom, `${where}.transferFrom`),
  }
}

/** 원고의 식별자를 지금 초안에 있는 것만 남긴다. 바꾼 수를 `tally`에 센다. */
function keepKnown(id: string | null, known: ReadonlySet<string>, tally: { replaced: number }) {
  if (id === null) return null
  if (known.has(id)) return id
  tally.replaced += 1
  return null
}

function quizQuestion(known: KnownQuizIds, tally: { replaced: number }) {
  return (value: unknown, where: string): DraftQuizQuestion => {
    const item = record(value, where, ['questionId', 'prompt', 'choices', 'explanation'])
    const questionId =
      item.questionId === undefined ? null : nullableText(item.questionId, `${where}.questionId`)
    return {
      questionId: keepKnown(questionId, known.questionIds, tally),
      prompt: text(item.prompt, `${where}.prompt`),
      choices: list(item.choices, `${where}.choices`, (choice, at): DraftQuizChoice => {
        const row = record(choice, at, ['choiceId', 'text', 'correct'])
        const choiceId =
          row.choiceId === undefined ? null : nullableText(row.choiceId, `${at}.choiceId`)
        return {
          choiceId: keepKnown(choiceId, known.choiceIds, tally),
          text: text(row.text, `${at}.text`),
          correct: bool(row.correct, `${at}.correct`),
        }
      }),
      explanation: text(item.explanation, `${where}.explanation`),
    }
  }
}

const TOP_KEYS = [
  'articleId',
  'format',
  'title',
  'question',
  'readingOrder',
  'stageKey',
  'stagePosition',
  'blocks',
  'example',
  'sources',
  'terms',
  'quiz',
] as const

function readShape(
  value: unknown,
  articleId: string,
  known: KnownQuizIds,
  tally: { replaced: number },
): EditableDraft {
  const file = record(value, '원고', TOP_KEYS)
  if (file.articleId !== undefined && text(file.articleId, 'articleId') !== articleId) {
    throw new ShapeError(
      'articleId',
      `다른 글(${String(file.articleId)})의 원고입니다. 이 글은 ${articleId}입니다.`,
    )
  }
  const common = {
    title: text(file.title, 'title'),
    question: text(file.question, 'question'),
    readingOrder: number(file.readingOrder, 'readingOrder'),
    stageKey: text(file.stageKey, 'stageKey'),
    stagePosition: number(file.stagePosition, 'stagePosition'),
    example: file.example === undefined ? null : example(file.example, 'example'),
    sources: list(file.sources ?? [], 'sources', source),
    quiz: list(file.quiz ?? [], 'quiz', quizQuestion(known, tally)),
  }
  if (file.format === 'V1') {
    return {
      ...common,
      format: 'V1',
      blocks: list(file.blocks, 'blocks', blockV1),
      terms: list(file.terms, 'terms', termV1),
    }
  }
  if (file.format === 'V2') {
    return {
      ...common,
      format: 'V2',
      blocks: list(file.blocks, 'blocks', blockV2),
      terms: list(file.terms, 'terms', termV2),
    }
  }
  throw new ShapeError('format', 'V1 또는 V2여야 합니다.')
}

/**
 * 원고 JSON을 편집기 초안으로 읽는다.
 *
 * <p>순서대로 JSON 해석, 모양, 편집 문법을 본다. 어느 단계에서든 걸리면 편집 상태를 바꾸지 않도록 초안을
 * 돌려주지 않는다.
 */
export function readManuscript(
  source: string,
  articleId: string,
  known: KnownQuizIds,
): ManuscriptReadResult {
  let parsed: unknown
  try {
    parsed = JSON.parse(source)
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    return {
      ok: false,
      issues: [{ where: '원고 파일', message: `JSON으로 읽지 못했습니다. ${reason}` }],
    }
  }

  let draft: EditableDraft
  const tally = { replaced: 0 }
  try {
    draft = readShape(parsed, articleId, known, tally)
  } catch (error) {
    if (error instanceof ShapeError) {
      return { ok: false, issues: [{ where: error.where, message: error.message }] }
    }
    throw error
  }

  const syntax = toServerDraft(draft)
  if (!syntax.ok) {
    return {
      ok: false,
      issues: syntax.issues.map((issue) => ({
        where: issue.where,
        line: issue.line,
        column: issue.column,
        message: issue.message,
      })),
    }
  }
  return {
    ok: true,
    draft,
    ids: {
      newQuestions: draft.quiz.filter((question) => question.questionId === null).length,
      newChoices: draft.quiz.reduce(
        (count, question) =>
          count + question.choices.filter((choice) => choice.choiceId === null).length,
        0,
      ),
      replaced: tally.replaced,
    },
  }
}
