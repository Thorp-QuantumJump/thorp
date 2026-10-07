import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 배우기 커리큘럼 화면은 의미 토큰과 같은 색을 값으로 박지 않는다.
 *
 * <p>`index.css` 머리 주석은 "컴포넌트는 아래 의미 토큰을 사용한다"이고, 디자인 전환(`classic`)은 토큰 값만 바꾼다
 * (frontend/CLAUDE.md). 토큰과 같은 색을 hex로 박으면 그 자리만 전환을 따르지 않는다. 그래서 기본 `:root`의 `--thorp-*`
 * 토큰 값을 모두 모아, 커리큘럼 화면의 소스에 같은 색이 hex로 나오는지 본다. 지도 전용 회색처럼 토큰에 없는 색은
 * 그대로 쓸 수 있다.
 */

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

function rootTokenColors(): Map<string, string> {
  const css = readFileSync(resolve(sourceRoot, 'index.css'), 'utf-8')
  const start = css.indexOf(':root {')
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
  const block = css.slice(start, end)
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
  resolve(sourceRoot, 'components/learning/stageColors.ts'),
  resolve(sourceRoot, 'pages/LearningListPage.tsx'),
  resolve(sourceRoot, 'pages/LearningListPage.module.css'),
]

describe('배우기 커리큘럼 화면의 색', () => {
  it('기본 토큰 색을 읽어 온다', () => {
    const colors = rootTokenColors()
    // 브랜드 초록(rgb 표기)과 흐린 글자색이 들어 있어야 이 검사가 뜻이 있다.
    expect(colors.get('#03c75a')).toBe('--thorp-brand-primary')
    expect(colors.get('#8b928e')).toBeDefined()
  })

  it('의미 토큰과 같은 색을 hex로 박지 않는다', () => {
    const colors = rootTokenColors()
    const found: string[] = []
    for (const path of TARGETS) {
      const text = readFileSync(path, 'utf-8')
      for (const match of text.matchAll(/#[0-9a-fA-F]{6}\b/g)) {
        const token = colors.get(match[0].toLowerCase())
        if (token) found.push(`${path.slice(sourceRoot.length + 1)}: ${match[0]} → var(${token})`)
      }
    }
    expect(found).toEqual([])
  })
})
