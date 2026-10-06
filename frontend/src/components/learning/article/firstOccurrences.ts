import type { CurrentArticleBlock, Run, TermMentionRun } from '@/types/learning'

/**
 * 글에서 용어가 처음 나오는 자리를 정한다(013 data-model §5-4, research R18).
 *
 * <p><b>이 함수 한 곳에서만 정한다.</b> 서버는 언급 조각마다 다른 이름과 소유 여부만 채워 보내고, 어느
 * 자리에 붙일지는 화면이 정한다. 같은 순서를 두 곳에서 정의하면 둘이 어긋난다.
 *
 * <p><b>문서 순서로 한 번 훑는다.</b> 블록 순서 → 블록 안 필드 순서 → 조각 순서다. 핵심 요약은 글 맨 위에
 * 있으므로 그 안의 언급이 가장 먼저다.
 *
 * - 다른 이름(FR-006): 용어마다 처음 나오는 용어 블록이나 언급 조각 한 곳. 그 자리에 다른 이름이 없으면
 *   붙이지 않는다
 * - 도움말 표시(FR-009): 다른 글이 소유한 용어(`owned: false`)의 처음 나오는 언급 조각 한 곳. 이 글이
 *   소유한 용어에는 두지 않는다
 */
export type OccurrenceMarks = {
  /** 다른 이름을 붙일 자리의 열쇠(`runKey`·`termBlockKey`) */
  aliasAt: ReadonlySet<string>
  /** 도움말 표시를 둘 언급 조각의 열쇠(`runKey`) */
  helpAt: ReadonlySet<string>
}

/** 블록 안 조각 배열 하나의 이름. `content`, `lines.0`, `rows.1.2`처럼 블록 안 위치까지 담는다. */
export type RunField = string

/** 언급 조각 하나의 열쇠. */
export function runKey(blockIndex: number, field: RunField, runIndex: number): string {
  return `${blockIndex}/${field}/${runIndex}`
}

/** 용어 블록 하나의 열쇠. */
export function termBlockKey(blockIndex: number): string {
  return `${blockIndex}/term`
}

/** 블록 하나 안의 조각 배열을 필드 순서대로 늘어놓는다. 조각이 없는 블록은 빈 배열이다. */
export function runFieldsOf(block: CurrentArticleBlock): Array<[RunField, Run[]]> {
  switch (block.type) {
    case 'keySummary':
      return block.lines.map((line, index) => [`lines.${index}`, line])
    case 'paragraph':
    case 'hypothetical':
    case 'institutionNote':
      return [['content', block.content]]
    case 'misconception':
      return [
        ['wrong', block.wrong],
        ['correction', block.correction],
      ]
    case 'list':
      return block.items.map((item, index) => [`items.${index}`, item])
    case 'table':
      return block.rows.flatMap((row, rowIndex) =>
        row.map((cell, cellIndex): [RunField, Run[]] => [`rows.${rowIndex}.${cellIndex}`, cell]),
      )
    default:
      return []
  }
}

export function isTermMention(run: Run): run is TermMentionRun {
  return 'term' in run
}

export function firstOccurrences(blocks: readonly CurrentArticleBlock[]): OccurrenceMarks {
  const aliasAt = new Set<string>()
  const helpAt = new Set<string>()
  const seenForAlias = new Set<string>()
  const seenForHelp = new Set<string>()

  blocks.forEach((block, blockIndex) => {
    if (block.type === 'term') {
      if (!seenForAlias.has(block.termId)) {
        seenForAlias.add(block.termId)
        if (block.aliases.length > 0) aliasAt.add(termBlockKey(blockIndex))
      }
      return
    }
    for (const [field, runs] of runFieldsOf(block)) {
      runs.forEach((run, runIndex) => {
        if (!isTermMention(run)) return
        const key = runKey(blockIndex, field, runIndex)
        if (!seenForAlias.has(run.term)) {
          seenForAlias.add(run.term)
          if (run.aliases.length > 0) aliasAt.add(key)
        }
        if (!run.owned && !seenForHelp.has(run.term)) {
          seenForHelp.add(run.term)
          helpAt.add(key)
        }
      })
    }
  })

  return { aliasAt, helpAt }
}

/** 처음 나오는 자리에 붙이는 다른 이름. `이름 (다른 이름1, 다른 이름2)`의 괄호 부분이다(FR-006). */
export function aliasSuffix(aliases: readonly string[]): string {
  return ` (${aliases.join(', ')})`
}
