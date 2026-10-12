import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 배우기 커리큘럼 화면과 '나의 학습'·완료 카드는 의미 토큰과 같은 색을 값으로 박지 않는다.
 *
 * <p>`index.css` 머리 주석은 "컴포넌트는 아래 의미 토큰을 사용한다"이고, 디자인 전환(`classic`)은 토큰 값만 바꾼다
 * (frontend/CLAUDE.md). 토큰과 같은 색을 값으로 박으면 그 자리만 전환을 따르지 않는다. 그래서 기본 `:root`의
 * `--thorp-*` 토큰 값을 모두 모아, 이 화면들의 소스에 같은 색이 hex나 `rgb()`(투명도를 붙인 것 포함)로 나오는지 본다.
 * 투명도를 줄 때는 `color-mix(in srgb, var(--토큰) n%, transparent)`로 쓴다. 지도 전용 회색처럼 토큰에 없는 색은 그대로
 * 쓸 수 있다.
 */

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

/** `index.css`에서 `head`로 시작하는 규칙 블록 하나를 꺼낸다. */
function indexCssBlock(head: string): string {
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
function classicClearedTokens(): string[] {
  const block = indexCssBlock(":root[data-design='classic'] {")
  return [...block.matchAll(/(--thorp-[\w-]+):\s*initial\s*;/g)].map((match) => match[1])
}

function rootTokenColors(): Map<string, string> {
  const block = indexCssBlock(':root {')
  const colors = new Map<string, string>()
  for (const match of block.matchAll(/(--thorp-[\w-]+):\s*(#[0-9a-fA-F]{6}|rgb\((\d+) (\d+) (\d+)\))\s*;/g)) {
    const [, name, value, r, g, b] = match
    const hex = value.startsWith('#')
      ? value.toLowerCase()
      : `#${[r, g, b].map((channel) => Number(channel).toString(16).padStart(2, '0')).join('')}`
    if (!colors.has(hex)) colors.set(hex, name)
  }
  return colors
}

function sources(relativeDirectory: string): string[] {
  const directory = resolve(sourceRoot, relativeDirectory)
  return readdirSync(directory)
    .filter((name) => /\.(tsx?|css)$/.test(name) && !/\.test\.tsx?$/.test(name))
    .map((name) => join(directory, name))
}

const TARGETS = [
  ...sources('components/learning/curriculum'),
  ...sources('components/learning/me'),
  ...sources('components/learning/article').filter((path) =>
    /[\\/](Completion\w*|ResumeLine)\.(tsx|module\.css)$/.test(path),
  ),
  resolve(sourceRoot, 'components/learning/stageColors.ts'),
  resolve(sourceRoot, 'pages/LearningListPage.tsx'),
  resolve(sourceRoot, 'pages/LearningListPage.module.css'),
  resolve(sourceRoot, 'pages/LearningMePage.tsx'),
  resolve(sourceRoot, 'pages/LearningMePage.module.css'),
]

/**
 * 새 디자인에만 쓰는 규칙의 표지. `:global(:root:not([data-design='classic'])) .item { … }`처럼 classic 밖으로 한정한
 * 규칙은 classic에서 쓰이지 않으니, 그 안에서는 classic에서 비우는 토큰을 대체값 없이 써도 된다.
 */
const NEW_DESIGN_ONLY = "not([data-design='classic'])"

/**
 * 주석을 지운 소스에서 `index` 자리의 선언이 classic에서도 쓰이는가. 그 선언이 속한 규칙의 선택자(바로 앞 `{`와 그 앞의
 * `}`·`;`·`{` 사이 글자)를 쉼표로 나눠, 하나라도 classic 밖으로 한정되지 않았으면 쓰인다고 본다.
 */
function appliesInClassic(css: string, index: number): boolean {
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

/** 소스에 값으로 쓴 색을 hex로 모은다. `#rrggbb`와 `rgb(r g b)`·`rgb(r, g, b)`(투명도를 붙인 것 포함)다. */
function writtenColors(text: string): { written: string; hex: string }[] {
  const hexes = [...text.matchAll(/#[0-9a-fA-F]{6}\b/g)].map((match) => ({
    written: match[0],
    hex: match[0].toLowerCase(),
  }))
  const rgbs = [...text.matchAll(/rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)[^)]*\)/g)].map((match) => ({
    written: match[0],
    hex: `#${[match[1], match[2], match[3]].map((channel) => Number(channel).toString(16).padStart(2, '0')).join('')}`,
  }))
  return [...hexes, ...rgbs]
}

describe('배우기 화면의 색', () => {
  it('기본 토큰 색을 읽어 온다', () => {
    const colors = rootTokenColors()
    // 브랜드 초록(rgb 표기)과 흐린 글자색이 들어 있어야 이 검사가 뜻이 있다.
    expect(colors.get('#03c75a')).toBe('--thorp-brand-primary')
    expect(colors.get('#8b928e')).toBeDefined()
  })

  it('검사 대상에 완료 카드와 나의 학습 화면이 들어 있다', () => {
    const names = TARGETS.map((path) => path.slice(sourceRoot.length + 1).replace(/\\/g, '/'))
    expect(names).toContain('components/learning/article/CompletionScene.module.css')
    expect(names).toContain('components/learning/article/CompletionCard.tsx')
    expect(names).toContain('pages/LearningMePage.module.css')
  })

  it('값으로 쓴 rgb() 색도 hex로 읽는다', () => {
    expect(writtenColors('a { color: rgb(3 199 90 / 45%); fill: rgb(1, 23, 10); }').map((c) => c.hex)).toEqual([
      '#03c75a',
      '#01170a',
    ])
  })

  it('classic 밖으로 한정한 규칙만 classic에서 쓰이지 않는다고 본다', () => {
    const rule = (selector: string) => `${selector} { padding: var(--thorp-pad-card); }`
    const scoped = rule(`:global(:root:${NEW_DESIGN_ONLY}) :is(.item, .row)`)
    expect(appliesInClassic(scoped, scoped.indexOf('var('))).toBe(false)
    const mixed = rule(`:global(:root:${NEW_DESIGN_ONLY}) .item, .row`)
    expect(appliesInClassic(mixed, mixed.indexOf('var('))).toBe(true)
  })

  it('classic에서도 쓰는 규칙은 classic에서 비우는 토큰을 예전 값과 함께 쓴다', () => {
    const cleared = classicClearedTokens()
    // 확인 창 배경막처럼 개편 뒤 생긴 토큰이 들어 있어야 이 검사가 뜻이 있다.
    expect(cleared).toContain('--thorp-overlay')
    const found: string[] = []
    for (const path of TARGETS) {
      const text = readFileSync(path, 'utf-8').replace(/\/\*[\s\S]*?\*\//g, '')
      for (const match of text.matchAll(/var\((--thorp-[\w-]+)\s*\)/g)) {
        if (cleared.includes(match[1]) && appliesInClassic(text, match.index ?? 0))
          found.push(`${path.slice(sourceRoot.length + 1)}: ${match[0]}`)
      }
    }
    expect(found).toEqual([])
  })

  it('의미 토큰과 같은 색을 hex나 rgb()로 박지 않는다', () => {
    const colors = rootTokenColors()
    const found: string[] = []
    for (const path of TARGETS) {
      for (const { written, hex } of writtenColors(readFileSync(path, 'utf-8'))) {
        const token = colors.get(hex)
        if (token) found.push(`${path.slice(sourceRoot.length + 1)}: ${written} → var(${token})`)
      }
    }
    expect(found).toEqual([])
  })
})
