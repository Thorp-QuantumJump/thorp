import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * 011 화면의 금지 표현 검사 (T081 · SC-007 · 010 FR-022 ①).
 *
 * **대상 파일을 손으로 적지 않는다**(research R11). 디렉터리를 훑어 모으므로 파일을 새로 만들어도
 * 검사에서 빠지지 않는다 — 목록 방식이면 새 파일이 조용히 검사 밖으로 나간다.
 */
function walk(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = `${directory}/${entry.name}`
    if (entry.isDirectory()) return walk(path)
    return /\.tsx?$/.test(entry.name) ? [path] : []
  })
}

const sources = [
  ...walk('src/components/regimelink'),
  ...walk('src/pages').filter((path) => /\/RegimeLink[^/]*\.tsx?$/.test(path)),
].filter((path) => !/\.test\.tsx?$/.test(path))

/** 주석은 검사 대상이 아니다 — 화면에 나가는 문자열만 본다. */
function body(path: string): string {
  return readFileSync(path, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '')
}

/** SC-007 네 종류 + 010 금지 목록 13개를 문자열·정규식으로 판정한다. */
const forbidden: RegExp[] = [
  // SC-007: 추천·주문·'지금도 잘될'·원인 단정
  /추천/,
  /주문하기|주문 넣기|매수 주문|매도 주문/,
  /지금도/,
  /지금과 같은 여건/,
  /때문에 수익|덕분에 수익/,
  // 010 FR-022 ① 13개
  /대표 유사국면/,
  /충분히 유사/,
  /충분한 사례가 없|충분한 사례 없음/,
  /\d+\s*%\s*유사/,
  /유사도/,
  /모든 지표의 중요성이 같다/,
  /실제 투자 여건이 같다/,
  /유지율|retention|겹침 비율/,
  /당시 재현|당시 발표값 기준/,
  /수집 시각/,
  /실시간/,
  // 011이 더 막는 것: 확률·초과 수익·승패·연율화 확대
  /확률/,
  /초과 수익|초과수익/,
  /이겼|졌습니다|승자|승패/,
  /더 벌/,
]

describe('011 결과 화면의 금지 표현', () => {
  it('디렉터리 훑기로 대상 파일을 모은다', () => {
    expect(sources).toContain('src/components/regimelink/CaseStrategyResults.tsx')
    expect(sources).toContain('src/components/regimelink/fixedCopy.ts')
    expect(sources).toContain('src/pages/RegimeLinkPage.tsx')
    expect(sources.length).toBeGreaterThan(8)
  })

  it('금지 표현이 0건이다', () => {
    const found = sources.flatMap((path) =>
      forbidden
        .filter((pattern) => pattern.test(body(path)))
        .map((pattern) => `${path}: ${pattern}`),
    )
    expect(found).toEqual([])
  })

  it('유지율을 담을 필드가 타입에 없다', () => {
    const types = readFileSync('src/types/regimeLink.ts', 'utf8').replace(
      /\/\*[\s\S]*?\*\//g,
      '',
    )
    expect(types).not.toMatch(/retention/)
  })
})
