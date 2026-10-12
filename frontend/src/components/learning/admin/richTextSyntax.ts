import type { DraftRun } from '@/types/learningAdmin'

/**
 * 관리 편집기와 원고의 편집 문법(013 research R26).
 *
 * - `{{용어:termId|보이는 글자}}` → 용어 언급 조각 `{ term, text }`
 * - `{{출처:출처 이름|https://…}}` → 출처 표시 조각 `{ source: { label, url } }`
 * - 글자 `{{`는 `\{{`로 쓴다. 그 밖의 글자는 모두 글자 조각 `{ text }`이다.
 *
 * <p><b>이 문법은 관리 화면만 안다.</b> 서버와 독자 화면은 조각만 다룬다(R26). 문법을 서버로 보내지 않고,
 * 저장할 때 이 파일이 조각 배열로 바꾼다.
 *
 * <p><b>틀린 문법을 고쳐서 읽지 않는다.</b> 닫히지 않은 `{{`, 모르는 표시 이름, `|`가 없는 표시는 줄·열
 * 위치와 함께 오류로 돌려준다. 짐작해서 글자로 두면 운영자가 의도한 용어 언급이 글자로 게시된다(원칙 VI).
 * 조각 모양의 규칙 가운데 칸 맨 앞의 출처 표시(FR-007)와 http·https가 아닌 출처 주소(data-model §5-2)도
 * 여기서 위치와 함께 알린다. 길이·식별자 형식 같은 값 판정은 서버가 한다(계약 §5).
 *
 * <p><b>표시를 가르는 규칙.</b> 용어 표시는 첫 `|`에서(식별자에는 `|`가 없다), 출처 표시는 마지막 `|`에서
 * 가른다(출처 이름에는 `|`가 있을 수 있고 주소에는 보통 없다). `{`가 셋 넘게 이어지면 마지막 `{{`가 표시의
 * 시작이고 앞의 `{`는 글자다. 그래서 `{`로 끝나는 글자 바로 뒤에 표시를 둘 수 있다.
 *
 * <p><b>쓸 수 없는 것.</b> 표시 바로 앞의 `\`는 `\{{`로 읽힌다. 표시 안의 글자는 `}}`를 담거나 `}`로 끝날 수
 * 없다. 이 문법으로 나타낼 수 없는 조각이다.
 */

/** 오류 위치. 줄과 열은 1부터 센다. */
export type RichTextIssue = { line: number; column: number; message: string }

/** 오류 위치를 사람이 읽는 말로 쓴다. 편집 칸·원고·저장 거절이 같은 표기를 쓴다. */
export function describePosition(position: { line: number; column: number }): string {
  return `${position.line}줄 ${position.column}칸`
}

/** 이 칸이 허용하는 조각(data-model §5-3 '조각' 열). 글자 조각은 늘 허용한다. */
export type AllowedRuns = { term: boolean; source: boolean }

export type RichTextParseResult =
  { ok: true; runs: DraftRun[] } | { ok: false; issues: RichTextIssue[] }

export const ALL_RUNS: AllowedRuns = { term: true, source: true }

const OPEN = '{{'
const CLOSE = '}}'
const ESCAPED_OPEN = '\\{{'
const TERM_PREFIX = '용어:'
const SOURCE_PREFIX = '출처:'
const WEB_ADDRESS = /^https?:\/\//

function positionOf(source: string, index: number): { line: number; column: number } {
  const before = source.slice(0, index)
  const lineStart = before.lastIndexOf('\n') + 1
  return { line: before.split('\n').length, column: index - lineStart + 1 }
}

/**
 * 편집 문법 문자열을 조각 배열로 바꾼다. 빈 문자열은 빈 배열이다.
 *
 * <p>오류가 하나라도 있으면 조각을 돌려주지 않는다. 일부만 바꿔 저장하면 고치지 않은 표시가 사라진다.
 */
export function parseRichText(source: string, allow: AllowedRuns = ALL_RUNS): RichTextParseResult {
  const runs: DraftRun[] = []
  const issues: RichTextIssue[] = []
  let text = ''

  const issue = (index: number, message: string) => {
    issues.push({ ...positionOf(source, index), message })
  }
  const flushText = () => {
    if (text !== '') runs.push({ text })
    text = ''
  }

  let index = 0
  while (index < source.length) {
    if (source.startsWith(ESCAPED_OPEN, index)) {
      text += OPEN
      index += ESCAPED_OPEN.length
      continue
    }
    // `{{{…`이면 앞의 `{`는 글자이고 표시는 다음 자리에서 시작한다.
    if (!source.startsWith(OPEN, index) || source[index + OPEN.length] === '{') {
      text += source[index]
      index += 1
      continue
    }

    const close = source.indexOf(CLOSE, index + OPEN.length)
    if (close < 0) {
      issue(index, "'{{'를 닫는 '}}'가 없습니다. 글자 '{{'를 쓰려면 '\\{{'로 씁니다.")
      break
    }
    const body = source.slice(index + OPEN.length, close)
    const next = close + CLOSE.length

    if (body.includes(OPEN)) {
      issue(index, "표시 안에 '{{'가 있습니다. 앞의 표시를 '}}'로 닫았는지 확인해 주세요.")
      index = next
      continue
    }

    if (body.startsWith(TERM_PREFIX)) {
      const rest = body.slice(TERM_PREFIX.length)
      const bar = rest.indexOf('|')
      if (bar < 0) {
        issue(index, "용어 표시는 '{{용어:식별자|보이는 글자}}' 모양이어야 합니다.")
      } else if (rest.slice(0, bar) === '') {
        issue(index, '용어 표시의 식별자가 비어 있습니다.')
      } else if (rest.slice(bar + 1) === '') {
        issue(index, '용어 표시의 보이는 글자가 비어 있습니다.')
      } else if (!allow.term) {
        issue(index, '이 칸에는 용어 표시를 쓸 수 없습니다.')
      } else {
        flushText()
        runs.push({ term: rest.slice(0, bar), text: rest.slice(bar + 1) })
      }
    } else if (body.startsWith(SOURCE_PREFIX)) {
      const rest = body.slice(SOURCE_PREFIX.length)
      const bar = rest.lastIndexOf('|')
      if (bar < 0) {
        issue(index, "출처 표시는 '{{출처:출처 이름|https://…}}' 모양이어야 합니다.")
      } else if (rest.slice(bar + 1) === '') {
        issue(index, '출처 표시의 주소가 비어 있습니다.')
      } else if (!WEB_ADDRESS.test(rest.slice(bar + 1))) {
        issue(index, '출처 표시의 주소는 http:// 또는 https://로 시작해야 합니다.')
      } else if (!allow.source) {
        issue(index, '이 칸에는 출처 표시를 쓸 수 없습니다.')
      } else if (runs.length === 0 && text === '') {
        issue(index, '출처 표시는 인용한 글자 바로 뒤에 둡니다. 칸 맨 앞에 둘 수 없습니다.')
      } else {
        flushText()
        runs.push({ source: { label: rest.slice(0, bar), url: rest.slice(bar + 1) } })
      }
    } else {
      issue(
        index,
        "'{{' 뒤에는 '용어:'나 '출처:'가 와야 합니다. 글자 '{{'를 쓰려면 '\\{{'로 씁니다.",
      )
    }
    index = next
  }
  flushText()

  return issues.length === 0 ? { ok: true, runs } : { ok: false, issues }
}

/**
 * 조각 배열을 편집 문법 문자열로 바꾼다. `parseRichText`의 반대다.
 *
 * <p>글자 조각의 `{{`는 `\{{`로 쓴다. 이웃한 글자 조각은 한 덩어리가 되어 다시 읽으면 조각 하나가 된다
 * (보이는 글자는 같다).
 */
export function formatRichText(runs: readonly DraftRun[]): string {
  return runs
    .map((run) => {
      if ('source' in run)
        return `${OPEN}${SOURCE_PREFIX}${run.source.label}|${run.source.url}${CLOSE}`
      if ('term' in run) return `${OPEN}${TERM_PREFIX}${run.term}|${run.text}${CLOSE}`
      return run.text.split(OPEN).join(ESCAPED_OPEN)
    })
    .join('')
}

/**
 * 서버가 세는 글자 수다. 필드 안 글자·용어 언급 조각의 `text` 합이며 출처 표시는 세지 않는다
 * (data-model §5-2).
 */
export function richTextLength(runs: readonly DraftRun[]): number {
  return runs.reduce((total, run) => total + ('text' in run ? run.text.length : 0), 0)
}
