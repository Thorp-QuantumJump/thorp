import type {
  AdminDraftBlockV1,
  AdminDraftBlockV2,
  AdminDraftCommon,
  AdminDraftExample,
  AdminDraftSource,
  AdminDraftTermV1,
  AdminDraftTermV2,
  AdminLearningDraftV1,
  AdminLearningDraftV2,
  ArticleFormat,
  DraftDiagramSeries,
  DraftQuizChoice,
  DraftQuizQuestion,
  DraftRun,
  DraftSourceRef,
} from '@/types/learningAdmin'

/**
 * 배우기 관리 초안의 모양(013 data-model §5~§7, 관리 계약 §4·§5)을 한 곳에 둔다.
 *
 * <p>서버 응답 해석(`api/learningAdmin.ts`), 원고 해석(`components/learning/admin/manuscript.ts`), 탭 보관본 확인
 * (`adminDraftStorage.ts`)이 같은 모양 지식을 쓴다. 블록 종류마다 어떤 필드가 있는지는 `V1_BLOCK_FIELDS`·
 * `V2_BLOCK_FIELDS` 표 하나가 정하고, 타입 검사가 표와 DTO 타입(`types/learningAdmin.ts`)이 어긋나지 않게 본다.
 *
 * <p>해석 방식이 다른 것은 셋이다. 글자 조각 필드를 무엇으로 읽는지(서버는 조각 배열, 원고·보관본은 편집 문법
 * 문자열), 모르는 키를 거절하는지(원고만 거절한다. 서버가 필드를 늘려도 화면은 읽는다), 문제·보기 식별자를
 * 바꾸는지(원고 가져오기만). **빠진 키는 어느 방식이든 거절한다.** 빈 값으로 채우면 원고에서 빠진 확인 문제가
 * 저장에서 지워진다(원칙 VI). 예외는 계약이 '없을 수 있다'고 정한 9월 판 출처의 `url` 하나다.
 */

/** 모양이 틀린 자리와 이유. `where`가 `''`이면 맨 위(초안 전체)다. */
export class ShapeError extends Error {
  readonly where: string

  constructor(where: string, message: string) {
    super(message)
    this.where = where
  }
}

export type Reader<T> = (value: unknown, where: string) => T

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 경로를 잇는다. 맨 위(`''`)에서는 키만 쓴다. */
function pathOf(where: string, key: string | number): string {
  if (typeof key === 'number') return `${where}[${key}]`
  return where === '' ? key : `${where}.${key}`
}

export const readText: Reader<string> = (value, where) => {
  if (typeof value !== 'string') throw new ShapeError(where, '문자열이어야 합니다.')
  return value
}

const readBoolean: Reader<boolean> = (value, where) => {
  if (typeof value !== 'boolean') throw new ShapeError(where, 'true 또는 false여야 합니다.')
  return value
}

const readNumber: Reader<number> = (value, where) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new ShapeError(where, '숫자여야 합니다.')
  }
  return value
}

export function listOf<T>(read: Reader<T>): Reader<T[]> {
  return (value, where) => {
    if (!Array.isArray(value)) throw new ShapeError(where, '배열이어야 합니다.')
    return value.map((item, index) => read(item, pathOf(where, index)))
  }
}

function nullableOf<T>(read: Reader<T>): Reader<T | null> {
  return (value, where) => (value === null ? null : read(value, where))
}

/** 객체 하나를 읽는다. 엄격하면 모르는 키를 거절한다. */
function objectOf(
  value: unknown,
  where: string,
  keys: readonly string[],
  strict: boolean,
): Record<string, unknown> {
  if (!isObject(value)) throw new ShapeError(where, '객체여야 합니다.')
  if (strict) {
    const unknown = Object.keys(value).filter((key) => !keys.includes(key))
    if (unknown.length > 0) {
      throw new ShapeError(where, `모르는 키가 있습니다: ${unknown.join(', ')}`)
    }
  }
  return value
}

/** 키 하나를 읽는다. 키가 없으면 빈 값으로 채우지 않고 거절한다. */
function fieldOf<T>(
  object: Record<string, unknown>,
  key: string,
  where: string,
  read: Reader<T>,
): T {
  if (!Object.prototype.hasOwnProperty.call(object, key)) {
    throw new ShapeError(pathOf(where, key), '키가 없습니다.')
  }
  return read(object[key], pathOf(where, key))
}

/** 블록 필드 하나의 모양 */
type FieldKind =
  | 'text'
  | 'boolean'
  | 'textList'
  | 'nullableText'
  | 'nullableSource'
  | 'diagramKind'
  | 'series'
  | 'rich'
  | 'richList'
  | 'richGrid'

/** 필드 타입에 맞는 모양 이름. 표가 DTO 타입과 어긋나면 아래 `satisfies`가 컴파일에서 막는다. */
type KindOf<T> = [T] extends [DraftRun[][][]]
  ? 'richGrid'
  : [T] extends [DraftRun[][]]
    ? 'richList'
    : [T] extends [DraftRun[]]
      ? 'rich'
      : [T] extends [DraftDiagramSeries[]]
        ? 'series'
        : [T] extends [boolean]
          ? 'boolean'
          : [T] extends ['bar' | 'line']
            ? 'diagramKind'
            : [T] extends [string]
              ? 'text'
              : [T] extends [string[]]
                ? 'textList'
                : [T] extends [string | null]
                  ? 'nullableText'
                  : [T] extends [DraftSourceRef | null]
                    ? 'nullableSource'
                    : never

type BlockFieldTable<B extends { type: string }> = {
  readonly [T in B['type']]: {
    readonly [K in Exclude<keyof Extract<B, { type: T }>, 'type'>]: KindOf<
      Extract<B, { type: T }>[K]
    >
  }
}

/** V1 블록 종류와 필드(data-model §5-1) */
export const V1_BLOCK_FIELDS = {
  heading: { text: 'text' },
  paragraph: { text: 'text' },
  term: { termId: 'text' },
  hypothetical: { text: 'text' },
} as const satisfies BlockFieldTable<AdminDraftBlockV1>

/** V2 블록 종류와 필드(data-model §5-3). 순서는 편집기 '추가' 단추의 순서다. */
export const V2_BLOCK_FIELDS = {
  keySummary: { lines: 'richList' },
  heading: { text: 'text' },
  paragraph: { content: 'rich' },
  term: { termId: 'text' },
  hypothetical: { content: 'rich' },
  misconception: { wrong: 'rich', correction: 'rich' },
  list: { ordered: 'boolean', items: 'richList' },
  table: { caption: 'text', columns: 'textList', rows: 'richGrid' },
  diagram: {
    kind: 'diagramKind',
    title: 'text',
    unit: 'text',
    categories: 'textList',
    series: 'series',
    description: 'text',
  },
  institutionNote: { content: 'rich', asOf: 'nullableText', source: 'nullableSource' },
} as const satisfies BlockFieldTable<AdminDraftBlockV2>

export const V1_BLOCK_TYPES = Object.keys(V1_BLOCK_FIELDS) as AdminDraftBlockV1['type'][]
export const V2_BLOCK_TYPES = Object.keys(V2_BLOCK_FIELDS) as AdminDraftBlockV2['type'][]

/** V2 블록 하나의 필드 표. 글자 조각 필드를 고르는 쪽(`editableDraft.ts`)이 쓴다. */
export function v2FieldsOf(type: AdminDraftBlockV2['type']): Readonly<Record<string, FieldKind>> {
  return V2_BLOCK_FIELDS[type]
}

type RichAs<T, R> = [T] extends [DraftRun[][][]]
  ? R[][]
  : [T] extends [DraftRun[][]]
    ? R[]
    : [T] extends [DraftRun[]]
      ? R
      : T
type WithRich<B, R> = B extends unknown ? { [K in keyof B]: RichAs<B[K], R> } : never

/** 글자 조각 필드를 `R`로 둔 V2 블록. 서버 응답은 `DraftRun[]`, 편집기·원고·보관본은 편집 문법 문자열이다. */
export type V2BlockWith<R> = WithRich<AdminDraftBlockV2, R>

/** 글자 조각 필드를 `R`로 둔 초안 */
export type DraftWith<R> =
  AdminLearningDraftV1 | (Omit<AdminLearningDraftV2, 'blocks'> & { blocks: V2BlockWith<R>[] })

export type ShapeOptions<R> = {
  /** 글자 조각 필드 하나를 읽는다 */
  rich: Reader<R>
  /** 모르는 키를 거절한다(원고). 서버 응답은 키가 늘어도 읽는다 */
  strict: boolean
  /** 문제·보기 식별자를 바꾼다(원고 가져오기). 없으면 그대로 둔다 */
  mapQuizId?: (id: string | null, kind: 'question' | 'choice') => string | null
}

function readSourceRef(strict: boolean): Reader<DraftSourceRef> {
  return (value, where) => {
    const object = objectOf(value, where, ['label', 'url'], strict)
    return {
      label: fieldOf(object, 'label', where, readText),
      url: fieldOf(object, 'url', where, readText),
    }
  }
}

function readSeries(strict: boolean): Reader<DraftDiagramSeries> {
  return (value, where) => {
    const object = objectOf(value, where, ['name', 'values'], strict)
    return {
      name: fieldOf(object, 'name', where, readText),
      values: fieldOf(object, 'values', where, listOf(readNumber)),
    }
  }
}

const readDiagramKind: Reader<'bar' | 'line'> = (value, where) => {
  if (value !== 'bar' && value !== 'line')
    throw new ShapeError(where, 'bar 또는 line이어야 합니다.')
  return value
}

function readerOf<R>(kind: FieldKind, options: ShapeOptions<R>): Reader<unknown> {
  switch (kind) {
    case 'text':
      return readText
    case 'boolean':
      return readBoolean
    case 'textList':
      return listOf(readText)
    case 'nullableText':
      return nullableOf(readText)
    case 'nullableSource':
      return nullableOf(readSourceRef(options.strict))
    case 'diagramKind':
      return readDiagramKind
    case 'series':
      return listOf(readSeries(options.strict))
    case 'rich':
      return options.rich
    case 'richList':
      return listOf(options.rich)
    case 'richGrid':
      return listOf(listOf(options.rich))
  }
}

function readBlock<R>(
  table: Readonly<Record<string, Readonly<Record<string, FieldKind>>>>,
  format: ArticleFormat,
  options: ShapeOptions<R>,
): Reader<Record<string, unknown>> {
  const types = Object.keys(table)
  return (value, where) => {
    if (!isObject(value)) throw new ShapeError(where, '객체여야 합니다.')
    const type = value.type
    if (typeof type !== 'string' || !Object.prototype.hasOwnProperty.call(table, type)) {
      throw new ShapeError(
        pathOf(where, 'type'),
        `${format} 블록 종류(${types.join('·')})가 아닙니다.`,
      )
    }
    const fields = table[type]
    const object = objectOf(value, where, ['type', ...Object.keys(fields)], options.strict)
    const block: Record<string, unknown> = { type }
    for (const [key, kind] of Object.entries(fields)) {
      block[key] = fieldOf(object, key, where, readerOf(kind, options))
    }
    return block
  }
}

function readExample(strict: boolean): Reader<AdminDraftExample> {
  const readSector: Reader<AdminDraftExample['sectors'][number]> = (value, where) => {
    const object = objectOf(value, where, ['code', 'weightTenths'], strict)
    return {
      code: fieldOf(object, 'code', where, readText),
      weightTenths: fieldOf(object, 'weightTenths', where, readNumber),
    }
  }
  return (value, where) => {
    const object = objectOf(value, where, ['sectors'], strict)
    return { sectors: fieldOf(object, 'sectors', where, listOf(readSector)) }
  }
}

function readSource(strict: boolean): Reader<AdminDraftSource> {
  return (value, where) => {
    const object = objectOf(value, where, ['label', 'url'], strict)
    // 계약이 'url은 없을 수 있다'고 정했고 서버는 null인 주소를 키째 뺀다. 키가 없는 것과 null을 같은 뜻으로 받는다.
    const url = Object.prototype.hasOwnProperty.call(object, 'url')
      ? nullableOf(readText)(object.url, pathOf(where, 'url'))
      : null
    return { label: fieldOf(object, 'label', where, readText), url }
  }
}

function readTermV1(strict: boolean): Reader<AdminDraftTermV1> {
  return (value, where) => {
    const object = objectOf(value, where, ['termId', 'label', 'sentence'], strict)
    return {
      termId: fieldOf(object, 'termId', where, readText),
      label: fieldOf(object, 'label', where, readText),
      sentence: fieldOf(object, 'sentence', where, readText),
    }
  }
}

const TERM_V2_KEYS = ['termId', 'label', 'aliases', 'meaning', 'reading', 'caveat', 'transferFrom']

function readTermV2(strict: boolean): Reader<AdminDraftTermV2> {
  return (value, where) => {
    const object = objectOf(value, where, TERM_V2_KEYS, strict)
    return {
      termId: fieldOf(object, 'termId', where, readText),
      label: fieldOf(object, 'label', where, readText),
      aliases: fieldOf(object, 'aliases', where, listOf(readText)),
      meaning: fieldOf(object, 'meaning', where, readText),
      reading: fieldOf(object, 'reading', where, readText),
      caveat: fieldOf(object, 'caveat', where, readText),
      transferFrom: fieldOf(object, 'transferFrom', where, nullableOf(readText)),
    }
  }
}

function readQuizQuestion<R>(options: ShapeOptions<R>): Reader<DraftQuizQuestion> {
  const mapId = options.mapQuizId ?? ((id: string | null) => id)
  const readId = nullableOf(readText)
  const readChoice: Reader<DraftQuizChoice> = (value, where) => {
    const object = objectOf(value, where, ['choiceId', 'text', 'correct'], options.strict)
    return {
      choiceId: mapId(fieldOf(object, 'choiceId', where, readId), 'choice'),
      text: fieldOf(object, 'text', where, readText),
      correct: fieldOf(object, 'correct', where, readBoolean),
    }
  }
  return (value, where) => {
    const object = objectOf(
      value,
      where,
      ['questionId', 'prompt', 'choices', 'explanation'],
      options.strict,
    )
    return {
      questionId: mapId(fieldOf(object, 'questionId', where, readId), 'question'),
      prompt: fieldOf(object, 'prompt', where, readText),
      choices: fieldOf(object, 'choices', where, listOf(readChoice)),
      explanation: fieldOf(object, 'explanation', where, readText),
    }
  }
}

const readFormat: Reader<ArticleFormat> = (value, where) => {
  if (value !== 'V1' && value !== 'V2') throw new ShapeError(where, 'V1 또는 V2여야 합니다.')
  return value
}

/** 초안의 키. 원고는 여기에 `articleId`를 더한다. */
const DRAFT_KEYS = [
  'title',
  'question',
  'readingOrder',
  'stageKey',
  'stagePosition',
  'example',
  'format',
  'blocks',
  'sources',
  'terms',
  'quiz',
]

/** 형식과 관계없이 모든 초안이 가진 필드만 고른다. */
export function pickCommon(draft: AdminDraftCommon): AdminDraftCommon {
  return {
    title: draft.title,
    question: draft.question,
    readingOrder: draft.readingOrder,
    example: draft.example,
    stageKey: draft.stageKey,
    stagePosition: draft.stagePosition,
  }
}

/**
 * 초안 한 벌을 읽는다. **`format`을 먼저 읽는다.** 같은 `type` 이름의 블록이 형식마다 필드가 달라 형식을
 * 모르고는 블록을 읽을 수 없다(research R16). 틀린 곳은 `ShapeError`로 던진다.
 *
 * @param extraKeys 초안 키 밖에 더 받을 맨 위 키(원고의 `articleId`)
 */
export function readDraftShape<R>(
  value: unknown,
  where: string,
  options: ShapeOptions<R>,
  extraKeys: readonly string[] = [],
): DraftWith<R> {
  const object = objectOf(value, where, [...DRAFT_KEYS, ...extraKeys], options.strict)
  const format = fieldOf(object, 'format', where, readFormat)
  const common: AdminDraftCommon = {
    title: fieldOf(object, 'title', where, readText),
    question: fieldOf(object, 'question', where, readText),
    readingOrder: fieldOf(object, 'readingOrder', where, readNumber),
    stageKey: fieldOf(object, 'stageKey', where, readText),
    stagePosition: fieldOf(object, 'stagePosition', where, readNumber),
    example: fieldOf(object, 'example', where, nullableOf(readExample(options.strict))),
  }
  const sources = fieldOf(object, 'sources', where, listOf(readSource(options.strict)))
  const quiz = fieldOf(object, 'quiz', where, listOf(readQuizQuestion(options)))

  if (format === 'V1') {
    const blocks = fieldOf(
      object,
      'blocks',
      where,
      listOf(readBlock(V1_BLOCK_FIELDS, format, options)),
    )
    return {
      ...common,
      format,
      sources,
      quiz,
      blocks: blocks as AdminDraftBlockV1[],
      terms: fieldOf(object, 'terms', where, listOf(readTermV1(options.strict))),
    }
  }
  const blocks = fieldOf(
    object,
    'blocks',
    where,
    listOf(readBlock(V2_BLOCK_FIELDS, format, options)),
  )
  return {
    ...common,
    format,
    sources,
    quiz,
    blocks: blocks as V2BlockWith<R>[],
    terms: fieldOf(object, 'terms', where, listOf(readTermV2(options.strict))),
  }
}
