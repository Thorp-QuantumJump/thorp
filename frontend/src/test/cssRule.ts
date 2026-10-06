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
