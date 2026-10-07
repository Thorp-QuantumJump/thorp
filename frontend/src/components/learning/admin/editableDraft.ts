import type {
  AdminDraftBlockV2,
  AdminLearningDraft,
  AdminLearningDraftV2,
  ArticleFormat,
  DraftRun,
  EditableDraft,
} from '@/types/learningAdmin'
import { BLOCK_LABELS, describeField } from './draftLabels'
import { formatRichText, parseRichText, type AllowedRuns } from './richTextSyntax'

/**
 * 서버 초안과 편집기 초안 사이를 바꾼다(013 research R26).
 *
 * <p>서버와 주고받는 V2 초안의 글자 조각 필드는 조각 배열이고, 편집기·원고·탭 보관본은 편집 문법
 * 문자열이다. 블록의 나머지 모양은 같다. V1 초안에는 글자 조각 필드가 없어 그대로 지나간다.
 */

/** 블록 필드마다 허용하는 조각(data-model §5-3 '조각' 열). 편집기와 저장 변환이 같은 표를 쓴다. */
export const FIELD_RUNS = {
  keySummary: { term: true, source: true },
  paragraph: { term: true, source: true },
  hypothetical: { term: true, source: false },
  misconception: { term: true, source: true },
  list: { term: false, source: true },
  table: { term: false, source: true },
  institutionNote: { term: true, source: true },
} satisfies Record<string, AllowedRuns>

/** 저장 전에 찾은 문법 오류 하나. `where`는 사람이 읽는 칸 이름이다. */
export type DraftSyntaxIssue = { where: string; line: number; column: number; message: string }

export type ServerDraftResult =
  { ok: true; draft: AdminLearningDraft } | { ok: false; issues: DraftSyntaxIssue[] }

/**
 * V2 블록의 글자 조각 필드를 모두 바꾼다. 변환 방향(조각 ↔ 문자열)과 관계없이 같은 필드를 같은 순서로
 * 지나도록 한 곳에 둔다. `field`는 data-model §8-4의 표기다.
 */
function mapBlockRuns<A, B>(
  block: AdminDraftBlockV2<A>,
  convert: (value: A, field: string, allow: AllowedRuns) => B,
): AdminDraftBlockV2<B> {
  switch (block.type) {
    case 'keySummary':
      return {
        type: 'keySummary',
        lines: block.lines.map((line, index) =>
          convert(line, `lines[${index}]`, FIELD_RUNS.keySummary),
        ),
      }
    case 'paragraph':
      return { type: 'paragraph', content: convert(block.content, 'content', FIELD_RUNS.paragraph) }
    case 'hypothetical':
      return {
        type: 'hypothetical',
        content: convert(block.content, 'content', FIELD_RUNS.hypothetical),
      }
    case 'misconception':
      return {
        type: 'misconception',
        wrong: convert(block.wrong, 'wrong', FIELD_RUNS.misconception),
        correction: convert(block.correction, 'correction', FIELD_RUNS.misconception),
      }
    case 'list':
      return {
        type: 'list',
        ordered: block.ordered,
        items: block.items.map((item, index) => convert(item, `items[${index}]`, FIELD_RUNS.list)),
      }
    case 'table':
      return {
        type: 'table',
        caption: block.caption,
        columns: [...block.columns],
        rows: block.rows.map((row, rowIndex) =>
          row.map((cell, cellIndex) =>
            convert(cell, `rows[${rowIndex}][${cellIndex}]`, FIELD_RUNS.table),
          ),
        ),
      }
    case 'institutionNote':
      return {
        type: 'institutionNote',
        content: convert(block.content, 'content', FIELD_RUNS.institutionNote),
        asOf: block.asOf,
        source: block.source,
      }
    case 'heading':
    case 'term':
      return { ...block }
    case 'diagram':
      return {
        ...block,
        categories: [...block.categories],
        series: block.series.map((series) => ({ ...series, values: [...series.values] })),
      }
  }
}

/** 서버 초안을 편집기 초안으로 바꾼다. 실패하지 않는다. */
export function toEditableDraft(draft: AdminLearningDraft): EditableDraft {
  if (draft.format === 'V1') return draft
  return {
    ...draft,
    blocks: draft.blocks.map((block) => mapBlockRuns(block, (runs) => formatRichText(runs))),
  }
}

/**
 * 편집기 초안을 저장할 수 있는 서버 초안으로 바꾼다.
 *
 * <p><b>문법 오류가 하나라도 있으면 바꾸지 않는다.</b> 오류를 모두 모아 돌려준다. 한 칸만 고치고 다시 누르기를
 * 되풀이하지 않게 하려는 것이다.
 */
export function toServerDraft(editable: EditableDraft): ServerDraftResult {
  if (editable.format === 'V1') return { ok: true, draft: editable }

  const issues: DraftSyntaxIssue[] = []
  const blocks = editable.blocks.map((block, index) =>
    mapBlockRuns<string, DraftRun[]>(block, (value, field, allow) => {
      const parsed = parseRichText(value, allow)
      if (parsed.ok) return parsed.runs
      const where = `본문 ${index + 1}번째 블록(${BLOCK_LABELS[block.type]}) · ${describeField(field)}`
      for (const issue of parsed.issues) issues.push({ where, ...issue })
      return []
    }),
  )
  if (issues.length > 0) return { ok: false, issues }
  const draft: AdminLearningDraftV2 = { ...editable, blocks }
  return { ok: true, draft }
}

/**
 * 형식을 바꾼 빈 본문이다(research R16). 제목·질문·배치·예시는 남기고 본문·용어·확인 문제·출처를 비운다.
 *
 * <p>형식 사이를 짐작해 옮기지 않는다. V2의 표·도식·확인 문제는 V1에 자리가 없어, 옮기면 일부가 말없이
 * 사라진다(원칙 VI). 운영자는 바꾸기 전에 원고를 내보내 두고, 바꾼 뒤 그 형식의 원고를 가져온다(R25).
 */
export function withEmptyBody(draft: EditableDraft, format: ArticleFormat): EditableDraft {
  const common = {
    title: draft.title,
    question: draft.question,
    readingOrder: draft.readingOrder,
    example: draft.example,
    stageKey: draft.stageKey,
    stagePosition: draft.stagePosition,
    sources: [],
    quiz: [],
  }
  return format === 'V1'
    ? { ...common, format: 'V1', blocks: [], terms: [] }
    : { ...common, format: 'V2', blocks: [], terms: [] }
}
