import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * 007 화면 코드는 전략을 모른 채로 동작해야 한다(FR-025). 전략 ID·전략명도, 전략에 따라 갈라지는
 * 분기도 두지 않는다 — 등록표에 전략이 늘거나 이름이 바뀌면 화면만 옛 세계를 말하게 된다.
 *
 * **대상 파일을 손으로 적지 않는다.** 이 검사는 원래 `SpaceEditor.test.tsx` 안에서 손으로 적은
 * 목록을 훑었고, 그 목록은 M4 시점에서 멈춰 있었다. M6·M8에서 생긴 파일 열세 개가 검사 밖에 있었고
 * 그중 하나(`CompareHandoff`)에 전략명이 박힌 채로 두 묶음이 지나갔다(S15P21E205-222).
 * 목록은 사람이 갱신해야 하고, 갱신을 잊은 것이 곧 검사 구멍이다. 그래서 훑어서 모은다.
 *
 * 훑는 것은 `.ts`·`.tsx`다. CSS Module에는 전략이 나타날 자리가 없다(클래스 이름은 역할로 짓는다).
 */

/** 훑기 시작점. vitest의 작업 디렉터리는 `frontend/`다. */
const SOURCE_ROOT = 'src'

/** 007 전용 디렉터리. 이 아래 운영 파일은 묻지 않고 전부 대상이다. */
const OWNED_DIRECTORIES = ['src/components/paramsearch']

/**
 * 다른 spec과 섞여 있는 자리에서 007 파일을 알아보는 표시 — 007 모듈을 가져다 쓰면 007 코드다.
 * 목록이 놓쳤던 `useSearchCandidates`가 이 규칙으로 들어온다.
 */
const IMPORTS_PARAM_SEARCH =
  /from '@\/(api\/paramSearch|types\/paramSearch|utils\/paramSearchLabels|components\/paramsearch\/|hooks\/useParamSearchStatus|hooks\/useSearchCandidates)/

/** 이름 자체가 007을 말하는 파일. 가져다 쓸 것이 없는 뿌리 모듈이 이 규칙으로 들어온다. */
const NAMED_PARAM_SEARCH = /paramsearch/i

/** 테스트와 픽스처는 FR-025의 명시적 예외다 — 지어낸 전략으로 화면을 시험하는 것이 그들의 일이다. */
const EXCLUDED = /\.test\.tsx?$|^src\/test\//

/**
 * 등록표의 전략 ID와 이름. 원본은
 * `backend/src/main/resources/registry/parameter-registry-provisional.yml`이고 여기 적은 것은
 * **화면 코드에 나타나면 안 되는 낱말 목록**이다. 등록표에 전략이 늘면 이 목록에도 더한다.
 */
const registryWords = [
  'EQUAL_WEIGHT',
  'FIXED_WEIGHT',
  'INVERSE_VOLATILITY',
  'SECTOR_MOMENTUM',
  'MINIMUM_VARIANCE',
  '동일 비중',
  '고정 비중',
  '역변동성',
  '섹터 모멘텀',
  '최소분산',
]

/** 전략 분기의 흔한 형태. */
const branchingPatterns = [
  /switch\s*\(\s*[\w.]*[sS]trategyId/,
  /[\w.]*[sS]trategyId\s*===?\s*['"]/,
  /['"][A-Z][A-Z0-9_]{3,}['"]\s*===?\s*[\w.]*[sS]trategyId/,
]

function walk(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = `${directory}/${entry.name}`
    if (entry.isDirectory()) return walk(path)
    return /\.tsx?$/.test(entry.name) ? [path] : []
  })
}

/** 007 운영 코드(프론트). 목록이 아니라 훑기의 결과다. */
function collectProductionSources(): string[] {
  return walk(SOURCE_ROOT)
    .filter((path) => !EXCLUDED.test(path))
    .filter(
      (path) =>
        OWNED_DIRECTORIES.some((directory) => path.startsWith(`${directory}/`)) ||
        NAMED_PARAM_SEARCH.test(path) ||
        IMPORTS_PARAM_SEARCH.test(readFileSync(path, 'utf-8')),
    )
    .sort()
}

describe('007 화면 코드는 전략을 모른다', () => {
  const productionSources = collectProductionSources()

  it('검사 대상을 손 목록이 아니라 훑기로 모은다', () => {
    // 훑기가 망가지면 대상이 비고, 빈 목록은 아래 두 검사를 조용히 통과시킨다.
    // 그래서 훑기의 결과 자체를 먼저 단언한다.
    const ownedFiles = walk(OWNED_DIRECTORIES[0]).filter((path) => !EXCLUDED.test(path))
    expect(ownedFiles.length).toBeGreaterThan(0)
    expect(productionSources).toEqual(expect.arrayContaining(ownedFiles))

    // 007 전용 디렉터리 밖에 흩어진 파일들. 손 목록이 놓쳤던 것이 이쪽이다.
    expect(productionSources).toEqual(
      expect.arrayContaining([
        'src/api/paramSearch.ts',
        'src/types/paramSearch.ts',
        'src/utils/paramSearchLabels.ts',
        'src/hooks/useParamSearchStatus.ts',
        'src/hooks/useSearchCandidates.ts',
        'src/pages/ParamSearchPage.tsx',
      ]),
    )

    // 테스트·픽스처는 대상이 아니다 — 들어오면 지어낸 전략 때문에 검사가 늘 빨갛다.
    expect(productionSources.filter((path) => EXCLUDED.test(path))).toEqual([])
  })

  it('007 화면 코드에 등록표의 전략 ID·전략명이 없다', () => {
    const violations: string[] = []

    for (const source of productionSources) {
      const content = readFileSync(source, 'utf-8')
      for (const word of registryWords) {
        if (content.includes(word)) violations.push(`${source} → ${word}`)
      }
    }

    expect(violations).toEqual([])
  })

  it('007 화면 코드에 strategyId 분기가 없다', () => {
    const violations: string[] = []

    for (const source of productionSources) {
      const content = readFileSync(source, 'utf-8')
      for (const pattern of branchingPatterns) {
        const matched = pattern.exec(content)
        if (matched) violations.push(`${source} → ${matched[0]}`)
      }
    }

    expect(violations).toEqual([])
  })
})
