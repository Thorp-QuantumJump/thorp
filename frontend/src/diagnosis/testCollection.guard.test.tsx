import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 테스트 수집 경로가 조용히 빠지는 것을 막는 가드다.
 *
 * Vitest가 수집하는 파일은 `vite.config.ts`의 `test.include`가 정하는데 이 값이 허용 목록이라,
 * 목록에 걸리지 않는 `.test.ts`는 **실패하지 않고 그냥 수집되지 않는다.** `node --test`로 도는
 * 파일도 `package.json`의 `test:legacy` 인자 목록에 적힌 것만 실행된다. 두 목록 어디에도 걸리지
 * 않는 테스트 파일이 있으면 그 파일은 있으나 마나 하므로 여기서 실패시킨다.
 *
 * **`test.exclude`까지 읽어야 한다.** include에 걸려도 exclude가 도로 빼면 Vitest는 그 파일을
 * 돌리지 않는다. exclude를 보지 않으면 가드가 "수집됨"으로 오인해, 정작 아무도 돌리지 않는
 * 파일을 통과시킨다. 그러면 이 가드가 막으려던 상황이 그대로 일어난다.
 *
 * 이 파일의 확장자가 `.tsx`인 까닭은, 목록을 고치지 않아도 반드시 수집되는 패턴이
 * `src/**` + `*.test.tsx` 하나뿐이기 때문이다. 가드 자신이 수집되지 않으면 아무것도 지키지 못한다.
 */

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const sourceRoot = resolve(frontendRoot, 'src')

/** 어느 운영체제에서든 같은 문자열로 비교하기 위해 경로 구분자를 슬래시로 맞춘다. */
function toPosix(value: string): string {
  return value.replace(/\\/g, '/')
}

/** `src` 아래를 재귀로 훑어 확장자가 `.test.ts`인 파일의 상대 경로를 모은다. */
function collectPlainTypeScriptTests(directory: string = sourceRoot): string[] {
  const found: string[] = []

  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const absolutePath = resolve(directory, entry.name)

    if (entry.isDirectory()) {
      found.push(...collectPlainTypeScriptTests(absolutePath))
      continue
    }

    if (entry.isFile() && entry.name.endsWith('.test.ts')) {
      found.push(toPosix(absolutePath.slice(frontendRoot.length + 1)))
    }
  }

  return found
}

type PatternMatcher = (relativePath: string) => boolean

/**
 * 지금 쓰는 표현은 재귀 `dir/**` + `/*.ext`, 한 폴더 `dir/*.ext`, 정확 경로다.
 * **해석할 수 없는 형태가 들어오면 통과시키지 않고 예외를 던진다.** 모르는 패턴을 통과로
 * 처리하면 검사가 조용히 사라진다.
 */
export function compilePattern(pattern: string): PatternMatcher {
  const globToken = '**/*'
  const globIndex = pattern.indexOf(globToken)

  if (globIndex === -1) {
    const singleGlobIndex = pattern.indexOf('*')

    if (singleGlobIndex !== -1 && pattern.indexOf('*', singleGlobIndex + 1) === -1) {
      const prefix = pattern.slice(0, singleGlobIndex)
      const suffix = pattern.slice(singleGlobIndex + 1)

      if (prefix.endsWith('/') && !suffix.includes('/') && !pattern.includes('{') && !pattern.includes('?')) {
        return (relativePath) => {
          if (!relativePath.startsWith(prefix) || !relativePath.endsWith(suffix)) return false
          const wildcardValue = relativePath.slice(prefix.length, relativePath.length - suffix.length)
          return wildcardValue.length > 0 && !wildcardValue.includes('/')
        }
      }
    }

    if (pattern.includes('*') || pattern.includes('{') || pattern.includes('?')) {
      throw new Error(`해석할 수 없는 수집 패턴이다: ${pattern}`)
    }
    return (relativePath) => relativePath === pattern
  }

  const prefix = pattern.slice(0, globIndex)
  const suffix = pattern.slice(globIndex + globToken.length)

  if (!prefix.endsWith('/') || prefix.includes('*') || suffix.includes('*') || suffix.includes('{')) {
    throw new Error(`해석할 수 없는 수집 패턴이다: ${pattern}`)
  }

  return (relativePath) =>
    relativePath.startsWith(prefix) &&
    relativePath.endsWith(suffix) &&
    relativePath.length > prefix.length + suffix.length
}

/**
 * `test.include` 값을 읽는다. 설정 모듈을 가져오는 쪽을 먼저 쓰고, 플러그인 실행이 막히면
 * 파일을 텍스트로 읽어 배열 리터럴을 뽑는다. **둘 다 실패하면 건너뛰지 않고 예외를 던진다.**
 */
async function readViteIncludePatterns(): Promise<string[]> {
  try {
    const configModule = (await import('../../vite.config')) as {
      default?: { test?: { include?: unknown } }
    }
    const include = configModule.default?.test?.include

    if (Array.isArray(include) && include.every((value) => typeof value === 'string')) {
      return include as string[]
    }
  } catch {
    // 플러그인 실행이 막힌 경우다. 아래의 텍스트 해석으로 내려간다.
  }

  const source = readFileSync(resolve(frontendRoot, 'vite.config.ts'), 'utf8')
  const arrayLiteral = /include:\s*\[([\s\S]*?)\]/.exec(source)

  if (!arrayLiteral) {
    throw new Error('vite.config.ts에서 test.include 배열을 읽지 못했다. 이 검사를 건너뛰지 않는다.')
  }

  const patterns = [...arrayLiteral[1].matchAll(/'([^']+)'|"([^"]+)"/g)].map(
    (match) => match[1] ?? match[2],
  )

  if (patterns.length === 0) {
    throw new Error('vite.config.ts의 test.include가 비어 있다. 이 검사를 건너뛰지 않는다.')
  }

  return patterns
}

/**
 * `test.exclude` 값을 읽는다. **include와 달리 설정 모듈을 가져오지 않고 텍스트만 읽는다.**
 * 이유가 둘이다. 하나는 jsdom 워커에서 `vitest/config`를 가져오면 esbuild가 TextEncoder
 * invariant로 죽어 가드 자신이 실행되지 않는 것이고, 다른 하나는 이쪽이 오히려 정확하기
 * 때문이다. 우리 설정의 exclude는 `[...configDefaults.exclude, '우리가 쓴 값']` 꼴이라,
 * 따옴표 안을 뽑으면 **사람이 직접 적은 패턴만** 남는다. Vitest 기본값은 전부 `src` 밖을
 * 가리키므로 이 검사에 영향을 주지 않는다.
 *
 * **exclude 키가 있는데 읽지 못하면 건너뛰지 않고 예외를 던진다.** 못 읽은 것을 "제외 없음"
 * 으로 처리하면 가드가 실제보다 느슨해져, 아무도 돌리지 않는 파일을 통과시킨다.
 */
function readViteExcludePatterns(): string[] {
  const source = readFileSync(resolve(frontendRoot, 'vite.config.ts'), 'utf8')
  const arrayLiteral = /exclude:\s*\[([\s\S]*?)\]/.exec(source)

  if (!arrayLiteral) {
    if (/\bexclude:/.test(source)) {
      throw new Error('vite.config.ts에서 test.exclude 배열을 읽지 못했다. 이 검사를 건너뛰지 않는다.')
    }
    return []
  }

  return [...arrayLiteral[1].matchAll(/'([^']+)'|"([^"]+)"/g)].map(
    (match) => match[1] ?? match[2],
  )
}

/** `test:legacy`가 `node --test`에 넘기는 파일 목록을 읽는다. */
function readLegacyTestFiles(): string[] {
  const manifest = JSON.parse(readFileSync(resolve(frontendRoot, 'package.json'), 'utf8')) as {
    scripts?: Record<string, string>
  }
  const script = manifest.scripts?.['test:legacy']

  if (typeof script !== 'string') {
    throw new Error('package.json에 test:legacy 스크립트가 없다. 이 검사를 건너뛰지 않는다.')
  }

  const files = script.split(/\s+/).filter((token) => token.endsWith('.test.ts'))

  if (files.length === 0) {
    throw new Error('test:legacy 인자에서 테스트 파일을 하나도 찾지 못했다.')
  }

  return files
}

describe('테스트 수집 경로', () => {
  it('src 아래의 .test.ts를 실제로 찾아낸다', () => {
    const files = collectPlainTypeScriptTests()

    // 0개는 순회가 잘못되었다는 뜻이다. 빈 목록으로 통과시키면 아래 검사가 사라진다.
    expect(files.length).toBeGreaterThan(0)
  })

  it('모든 .test.ts가 Vitest 수집이나 test:legacy 목록 가운데 하나에 걸린다', async () => {
    const files = collectPlainTypeScriptTests()
    const included = (await readViteIncludePatterns()).map(compilePattern)
    const excluded = readViteExcludePatterns().map(compilePattern)
    const legacy = readLegacyTestFiles().map(compilePattern)

    // include에 걸려도 exclude가 도로 빼면 Vitest는 그 파일을 돌리지 않는다.
    const collectedByVitest = (file: string) =>
      included.some((matches) => matches(file)) && !excluded.some((matches) => matches(file))

    const uncollected = files.filter(
      (file) => !collectedByVitest(file) && !legacy.some((matches) => matches(file)),
    )

    expect(uncollected).toEqual([])
  })

  it('exclude된 파일은 include에 걸려도 Vitest 수집으로 세지 않는다', async () => {
    const included = (await readViteIncludePatterns()).map(compilePattern)
    const excluded = readViteExcludePatterns().map(compilePattern)
    const legacyOnly = 'src/api/market.test.ts'

    // node:test 전용 파일이라 include glob에는 분명히 걸린다. 그래서 exclude를 읽지 않으면
    // 가드가 이 파일을 Vitest가 돌리는 것으로 오인한다.
    expect(included.some((matches) => matches(legacyOnly))).toBe(true)
    expect(excluded.some((matches) => matches(legacyOnly))).toBe(true)

    // 실제로 돌려주는 곳은 test:legacy 하나뿐이다. 거기서 빠지면 위 검사가 실패해야 한다.
    expect(readLegacyTestFiles()).toContain(legacyOnly)
  })

  it('해석할 수 없는 형태의 패턴은 통과시키지 않고 실패시킨다', () => {
    expect(() => compilePattern('src/**/{a,b}.test.ts')).toThrow()
    expect(() => compilePattern('src/*/*.test.ts')).toThrow()
    expect(() => compilePattern('src/api/?.test.ts')).toThrow()
  })

  it('알고 있는 세 형태를 바르게 해석한다', () => {
    const glob = compilePattern('src/**/*.test.tsx')
    expect(glob('src/pages/LandingPage.test.tsx')).toBe(true)
    expect(glob('src/pages/LandingPage.test.ts')).toBe(false)
    expect(glob('src/.test.tsx')).toBe(false)

    const oneDirectory = compilePattern('src/api/*.test.ts')
    expect(oneDirectory('src/api/marketFlow.test.ts')).toBe(true)
    expect(oneDirectory('src/api/nested/marketFlow.test.ts')).toBe(false)

    const exact = compilePattern('src/api/auth.test.ts')
    expect(exact('src/api/auth.test.ts')).toBe(true)
    expect(exact('src/api/sectors.test.ts')).toBe(false)
  })
})
