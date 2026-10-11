import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 013 T196. 배우기 화면의 문구와 색(FR-031 표시 규칙, SC-017, quickstart 11-7, design-notes §4).
 *
 * <p>배우기 화면 문구와 `aria-label`에 잠금·예고 어휘('잠김'·'해금'·'열립니다'·'공개 예정'·'Coming soon'·'NEW')를 쓰지
 * 않는다. 배우기의 상태 표시에는 값의 오르내림을 뜻하는 빨강·파랑(`--thorp-up`·`--thorp-down`)을 쓰지 않는다. 주석에 적은
 * 금지 확인 문장은 화면 문구가 아니므로 주석을 걷어 낸 뒤 본다. '곧'은 일반 낱말과 섞이므로 화면 검토로 본다.
 */

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

function walk(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    if (statSync(path).isDirectory()) return walk(path)
    return /\.(tsx?|css)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

/** 관리 화면(`admin/`)은 운영자 화면이라 이 규칙의 대상이 아니다. 관리 화면의 '공개' 단추 문구는 운영 용어다. */
const TARGETS = [
  ...walk(resolve(sourceRoot, 'components/learning')).filter(
    (path) => !/[\\/]admin[\\/]/.test(path),
  ),
  ...readdirSync(resolve(sourceRoot, 'pages'))
    .filter((name) => /^Learning.*\.(tsx|css)$/.test(name) && !/\.test\.tsx$/.test(name))
    .map((name) => resolve(sourceRoot, 'pages', name)),
]

/** 블록 주석과 줄 주석을 걷어 낸다. 문자열 안의 `//`(주소)는 남긴다. */
function stripComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1')
}

const LOCK_WORDS = /잠김|해금|열립니다|공개 예정|Coming soon|NEW/

describe('배우기 화면의 쓰지 않는 말과 색', () => {
  it('대상 파일을 찾는다', () => {
    const names = TARGETS.map((path) => relative(sourceRoot, path).replace(/\\/g, '/'))
    expect(names).toContain('components/learning/me/SummaryPanel.tsx')
    expect(names).toContain('pages/LearningMePage.tsx')
    expect(names.some((name) => name.includes('/admin/'))).toBe(false)
  })

  it('화면 문구와 aria-label에 잠금·예고 어휘가 없다(SC-017)', () => {
    const found = TARGETS.flatMap((path) => {
      const text = stripComments(readFileSync(path, 'utf-8'))
      return text
        .split('\n')
        .filter((line) => LOCK_WORDS.test(line))
        .map((line) => `${relative(sourceRoot, path)}: ${line.trim()}`)
    })
    expect(found).toEqual([])
  })

  it('상태 표시에 값의 오르내림 색(--thorp-up·--thorp-down)을 쓰지 않는다', () => {
    const found = TARGETS.filter((path) =>
      /--thorp-(up|down)\b/.test(stripComments(readFileSync(path, 'utf-8'))),
    ).map((path) => relative(sourceRoot, path))
    expect(found).toEqual([])
  })
})
