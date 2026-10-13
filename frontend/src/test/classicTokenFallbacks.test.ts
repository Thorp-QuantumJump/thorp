import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { NEW_DESIGN_ONLY, appliesInClassic, classicClearedTokens } from './cssRule'

/**
 * classic(`<html data-design="classic">`)에서 비우는 토큰을 대체값 없이 읽는 자리가 `src` 어디에도 없는지 본다.
 *
 * <p>개편 뒤 새로 생긴 `--thorp-*` 토큰은 classic에서 `initial`로 비운다(frontend/CLAUDE.md). 비워진 토큰을 대체값 없이
 * 읽은 선언은 계산 시점에 무효가 되고, 상속되지 않는 속성은 초깃값으로 돌아간다. 모서리는 0이 되고 배경은 투명해진다.
 * 그래서 classic에도 쓰이는 규칙은 `var(--토큰, 예전 값)`으로 쓴다. classic 밖으로 한정한 규칙과 비우는 토큰을 다시
 * 정의하는 선언은 classic에서 문제를 만들지 않으므로 예외다.
 */

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** `src` 아래 스타일을 담는 소스(CSS와 인라인 스타일을 쓰는 TS·TSX). 테스트 파일은 일부러 어긴 예를 담으므로 뺀다. */
function styleSources(directory: string = sourceRoot): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return styleSources(path)
    return /\.(css|tsx?)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : []
  })
}

function sourceName(path: string): string {
  return relative(sourceRoot, path).replace(/\\/g, '/')
}

/**
 * `index` 자리의 `var()`가 classic에서 비우는 토큰을 다시 정의하는 선언(`--토큰: var(…)`) 안에 있는가. 값이 무효가 되면
 * 그 토큰은 비워진 것과 같고, 그 토큰을 읽는 자리에는 이 검사가 대체값을 요구한다. `index.css`의 `:root`가
 * `--thorp-content-width`를 `--thorp-page-width-flow`로 정의하는 줄이 그 예다.
 */
function redefinesClearedToken(css: string, index: number, cleared: readonly string[]): boolean {
  const start = Math.max(css.lastIndexOf(';', index), css.lastIndexOf('{', index))
  const property = /^\s*(--[\w-]+)\s*:/.exec(css.slice(start + 1, index))?.[1]
  return property !== undefined && cleared.includes(property)
}

describe('classic에서 비우는 토큰', () => {
  it('classic 밖으로 한정한 규칙만 classic에서 쓰이지 않는다고 본다', () => {
    const rule = (selector: string) => `${selector} { padding: var(--thorp-pad-card); }`
    const scoped = rule(`:global(:root:${NEW_DESIGN_ONLY}) :is(.item, .row)`)
    expect(appliesInClassic(scoped, scoped.indexOf('var('))).toBe(false)
    const mixed = rule(`:global(:root:${NEW_DESIGN_ONLY}) .item, .row`)
    expect(appliesInClassic(mixed, mixed.indexOf('var('))).toBe(true)
  })

  it('비우는 토큰을 다시 정의하는 선언만 대체값 없이 읽어도 된다고 본다', () => {
    const cleared = ['--thorp-content-width', '--thorp-page-width-flow']
    const css =
      ':root { --thorp-content-width: var(--thorp-page-width-flow); } .page { --gap: var(--thorp-page-width-flow); }'
    expect(redefinesClearedToken(css, css.indexOf('var('), cleared)).toBe(true)
    expect(redefinesClearedToken(css, css.lastIndexOf('var('), cleared)).toBe(false)
  })

  it('검사 대상에 src 전체의 스타일 소스가 들어 있다', () => {
    const names = styleSources().map(sourceName)
    expect(names).toContain('index.css')
    expect(names).toContain('components/auth/SocialLoginButtons.module.css')
    expect(names).toContain('pages/RegimeSearchPage.module.css')
    expect(names).toContain('components/ui/button.tsx')
    expect(names.filter((name) => /\.test\.tsx?$/.test(name))).toEqual([])
  })

  it('classic에도 쓰이는 규칙은 비우는 토큰을 예전 값과 함께 쓴다', () => {
    const cleared = classicClearedTokens()
    // 확인 창 배경막·모서리처럼 개편 뒤 생긴 토큰이 들어 있어야 이 검사가 뜻이 있다.
    expect(cleared).toContain('--thorp-overlay')
    expect(cleared).toContain('--thorp-radius-control')
    const found: string[] = []
    for (const path of styleSources()) {
      // 줄 번호가 어긋나지 않게 주석은 줄바꿈만 남기고 지운다.
      const text = readFileSync(path, 'utf-8').replace(/\/\*[\s\S]*?\*\//g, (comment) =>
        comment.replace(/[^\n]/g, ' '),
      )
      for (const match of text.matchAll(/var\((--thorp-[\w-]+)\s*\)/g)) {
        const index = match.index ?? 0
        if (!cleared.includes(match[1]) || !appliesInClassic(text, index)) continue
        if (redefinesClearedToken(text, index, cleared)) continue
        found.push(`${sourceName(path)}:${text.slice(0, index).split('\n').length} ${match[0]}`)
      }
    }
    expect(found).toEqual([])
  })
})
