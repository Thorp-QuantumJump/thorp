import { pickCommon, v2FieldsOf, type DraftWith, type V2BlockWith } from '@/api/learningAdminShape'
import type {
  AdminDraftBlockV2,
  AdminLearningDraft,
  ArticleFormat,
  DraftRun,
} from '@/types/learningAdmin'
import { BLOCK_LABELS, describeField } from './draftLabels'
import { formatRichText, parseRichText, type AllowedRuns } from './richTextSyntax'

/**
 * 서버 초안과 편집기 초안 사이를 바꾼다(013 research R26).
 *
 * <p>서버와 주고받는 V2 초안의 글자 조각 필드는 조각 배열이고, 편집기·원고·탭 보관본은 편집 문법
 * 문자열이다. 블록의 나머지 모양은 같다. 어느 필드가 글자 조각인지는 `api/learningAdminShape.ts`의 블록 필드
 * 표가 정하고, 여기서는 그 표를 따라 바꾼다. V1 초안에는 글자 조각 필드가 없어 그대로 지나간다.
 */

/** 편집기가 들고 있는 V2 블록. 글자 조각 필드가 편집 문법 문자열이다. */
export type EditableBlockV2 = V2BlockWith<string>

/** 관리 편집기가 들고 있는 초안이다. 원고 파일과 탭 보관본도 이 모양이다. */
export type EditableDraft = DraftWith<string>

const NO_RUNS: AllowedRuns = { term: false, source: false }

/**
 * 블록마다 글자 조각 필드가 허용하는 조각(data-model §5-3 '조각' 열). 편집기와 저장 변환이 같은 표를 쓴다.
 * 소제목·용어·도식에는 글자 조각 필드가 없다.
 */
export const FIELD_RUNS = {
  keySummary: { term: true, source: true },
  heading: NO_RUNS,
  paragraph: { term: true, source: true },
  term: NO_RUNS,
  hypothetical: { term: true, source: false },
  misconception: { term: true, source: true },
  list: { term: false, source: true },
  table: { term: false, source: true },
  diagram: NO_RUNS,
  institutionNote: { term: true, source: true },
} satisfies Record<AdminDraftBlockV2['type'], AllowedRuns>

/** 저장 전에 찾은 문법 오류 하나. `where`는 사람이 읽는 칸 이름이다. */
export type DraftSyntaxIssue = { where: string; line: number; column: number; message: string }

export type ServerDraftResult =
  { ok: true; draft: AdminLearningDraft } | { ok: false; issues: DraftSyntaxIssue[] }

/**
 * V2 블록의 글자 조각 필드를 모두 바꾼다. 블록 필드 표를 따라가므로 블록 종류마다 따로 쓰지 않는다. `field`는
 * data-model §8-4의 표기(`lines[1]`, `rows[0][1]` 등)다.
 */
function mapBlockRuns<A, B>(
  block: V2BlockWith<A>,
  convert: (value: A, field: string, allow: AllowedRuns) => B,
): V2BlockWith<B> {
  const allow = FIELD_RUNS[block.type]
  const source = block as Record<string, unknown>
  const mapped: Record<string, unknown> = { type: block.type }
  for (const [key, kind] of Object.entries(v2FieldsOf(block.type))) {
    const value = source[key]
    if (kind === 'rich') {
      mapped[key] = convert(value as A, key, allow)
    } else if (kind === 'richList') {
      mapped[key] = (value as A[]).map((item, index) => convert(item, `${key}[${index}]`, allow))
    } else if (kind === 'richGrid') {
      mapped[key] = (value as A[][]).map((row, rowIndex) =>
        row.map((cell, cellIndex) => convert(cell, `${key}[${rowIndex}][${cellIndex}]`, allow)),
      )
    } else {
      mapped[key] = value
    }
  }
  return mapped as V2BlockWith<B>
}

/** 서버 초안을 편집기 초안으로 바꾼다. 실패하지 않는다. */
export function toEditableDraft(draft: AdminLearningDraft): EditableDraft {
  if (draft.format === 'V1') return draft
  return {
    ...draft,
    blocks: draft.blocks.map((block) =>
      mapBlockRuns<DraftRun[], string>(block as V2BlockWith<DraftRun[]>, (runs) =>
        formatRichText(runs),
      ),
    ),
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
  return { ok: true, draft: { ...editable, blocks: blocks as AdminDraftBlockV2[] } }
}

/**
 * 형식을 바꾼 빈 본문이다(research R16). 제목·질문·배치·예시는 남기고 본문·용어·확인 문제·출처를 비운다.
 *
 * <p>형식 사이를 짐작해 옮기지 않는다. V2의 표·도식·확인 문제는 V1에 자리가 없어, 옮기면 일부가 말없이
 * 사라진다(원칙 VI). 운영자는 바꾸기 전에 원고를 내보내 두고, 바꾼 뒤 그 형식의 원고를 가져온다(R25).
 */
export function withEmptyBody(draft: EditableDraft, format: ArticleFormat): EditableDraft {
  const empty = { ...pickCommon(draft), sources: [], quiz: [], blocks: [], terms: [] }
  return format === 'V1' ? { ...empty, format: 'V1' } : { ...empty, format: 'V2' }
}
