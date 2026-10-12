import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** `src` 폴더. 이 파일(`src/test/cssRule.ts`)의 위치에서 푼다. */
const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/**
 * CSS 모듈 파일에서 선택자 하나의 선언 블록들을 꺼낸다.
 *
 * <p>Vitest는 CSS를 처리하지 않고 CSS 모듈을 아무 이름이나 돌려주는 대역으로 바꾼다. 그래서 요소의
 * `className`만 보는 단언은 규칙이 지워져도 통과한다. 화면 동작이 CSS 규칙에 달려 있으면(숨김, 목록 표시 등)
 * 이 함수로 규칙이 실제로 있는지와 무엇을 선언하는지 함께 확인한다.
 *
 * @param path `src`를 기준으로 한 CSS 파일 경로(예: `components/learning/article/ArticleBody.module.css`)
 * @param selector 줄 처음에서 시작하는 선택자 그대로(예: `.table caption`, `ol.list`)
 */
export function cssDeclarations(path: string, selector: string): string {
  const css = readFileSync(resolve(sourceRoot, path), 'utf-8')
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  // 같은 선택자로 시작하는 규칙이 여럿이면(다른 선택자와 묶인 규칙 포함) 모두 모은다.
  const matches = [...css.matchAll(new RegExp(`^${escaped}\\s*\\{([^}]*)\\}`, 'gm'))]
  if (matches.length === 0) throw new Error(`${path}에 ${selector} 규칙이 없습니다.`)
  return matches.map((match) => match[1]).join('\n')
}

/**
 * CSS 모듈 파일에서 미디어 블록 안의 선택자 하나의 선언 블록들을 꺼낸다.
 *
 * <p>움직임은 `prefers-reduced-motion: no-preference` 안에서만, 강제 색 모드의 윤곽은 `forced-colors: active` 안에
 * 둔다. `cssDeclarations`는 줄 처음의 규칙만 보므로 이런 규칙이 지워지거나 블록 밖으로 나가도 알아채지 못한다.
 *
 * @param media `@media` 뒤의 조건 그대로(예: `(prefers-reduced-motion: no-preference)`)
 * @param selector 블록 안에서 줄 처음(들여쓰기 뒤)에서 시작하는 선택자 그대로
 */
export function cssMediaDeclarations(path: string, media: string, selector: string): string {
  const css = readFileSync(resolve(sourceRoot, path), 'utf-8')
  const head = `@media ${media}`
  const blocks: string[] = []
  for (let start = css.indexOf(head); start !== -1; start = css.indexOf(head, start + head.length)) {
    const open = css.indexOf('{', start)
    let depth = 0
    for (let i = open; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1
      if (css[i] === '}') depth -= 1
      if (depth === 0) {
        blocks.push(css.slice(open + 1, i))
        break
      }
    }
  }
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = blocks.flatMap((block) => [
    ...block.matchAll(new RegExp(`^\\s*${escaped}\\s*\\{([^}]*)\\}`, 'gm')),
  ])
  if (matches.length === 0) throw new Error(`${path}의 ${head}에 ${selector} 규칙이 없습니다.`)
  return matches.map((match) => match[1]).join('\n')
}

/** `index.css`에서 `head`로 시작하는 규칙 블록 하나를 꺼낸다. */
export function indexCssBlock(head: string): string {
  const css = readFileSync(resolve(sourceRoot, 'index.css'), 'utf-8')
  const start = css.indexOf(head)
  let depth = 0
  let end = start
  for (let i = css.indexOf('{', start); i < css.length; i += 1) {
    if (css[i] === '{') depth += 1
    if (css[i] === '}') depth -= 1
    if (depth === 0) {
      end = i
      break
    }
  }
  return css.slice(start, end)
}

/**
 * classic에서 `initial`로 비우는 토큰. 개편 뒤 새로 생긴 토큰이라 각 자리는 `var(--토큰, 예전 값)`으로 써야 classic이
 * 예전 화면으로 돌아간다(frontend/CLAUDE.md).
 */
export function classicClearedTokens(): string[] {
  const block = indexCssBlock(":root[data-design='classic'] {")
  return [...block.matchAll(/(--thorp-[\w-]+):\s*initial\s*;/g)].map((match) => match[1])
}

/**
 * 새 디자인에만 쓰는 규칙의 표지. `:global(:root:not([data-design='classic'])) .item { … }`처럼 classic 밖으로 한정한
 * 규칙은 classic에서 쓰이지 않으니, 그 안에서는 classic에서 비우는 토큰을 대체값 없이 써도 된다.
 */
export const NEW_DESIGN_ONLY = "not([data-design='classic'])"

/**
 * 주석을 지운 소스에서 `index` 자리의 선언이 classic에서도 쓰이는가. 그 선언이 속한 규칙의 선택자(바로 앞 `{`와 그 앞의
 * `}`·`;`·`{` 사이 글자)를 쉼표로 나눠, 하나라도 classic 밖으로 한정되지 않았으면 쓰인다고 본다.
 */
export function appliesInClassic(css: string, index: number): boolean {
  const open = css.lastIndexOf('{', index)
  const start = Math.max(
    css.lastIndexOf('}', open),
    css.lastIndexOf(';', open),
    css.lastIndexOf('{', open - 1),
  )
  const selector = css.slice(start + 1, open)
  // `:is(.a, .b)`처럼 괄호 안에 있는 쉼표에서는 나누지 않는다.
  const selectors: string[] = []
  let depth = 0
  let from = 0
  for (let i = 0; i < selector.length; i += 1) {
    if (selector[i] === '(') depth += 1
    if (selector[i] === ')') depth -= 1
    if (selector[i] === ',' && depth === 0) {
      selectors.push(selector.slice(from, i))
      from = i + 1
    }
  }
  selectors.push(selector.slice(from))
  return selectors.some((part) => !part.includes(NEW_DESIGN_ONLY))
}
