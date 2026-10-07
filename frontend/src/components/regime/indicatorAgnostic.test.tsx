import { readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function walk(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = `${directory}/${entry.name}`
    if (entry.isDirectory()) return walk(path)
    return /\.tsx?$/.test(entry.name) ? [path] : []
  })
}

/** 010 운영 파일을 손 목록 없이 모은다. */
const sources = walk('src').filter((path) =>
  !/\.test\.tsx?$|^src\/test\//.test(path) &&
  (/^src\/components\/regime\//.test(path) || /^(src\/(api|types)\/regime\.ts|src\/hooks\/useRegimeSearch\.ts|src\/pages\/RegimeSearchPage\.tsx)$/.test(path) ||
    // 시장 흐름 분야 상세 안에서 010 결과를 보이는 자리(009 FR-019)도 같은 경계를 따른다.
    /^(src\/components\/marketflow\/(FieldPastCompare|CompositePast|PastCaseChooser|PastFootnotes)\.tsx|src\/hooks\/usePastCases\.ts)$/.test(path)),
)

// 008 등록표의 분야·지표 ID와 이름(data/src/data_pipeline/macro/registry.py·display.py)과 종합 비교 묶음 이름
// (data/config/regime-phase-v3.json). 검사는 화면의 도메인 상수 유입을 막는다. ID는 대소문자를 가리지 않고 찾아
// 백엔드 enum 이름(PRODUCTION 등)도 함께 막되, 식별자 경계에서만 찾아 collaborate 같은 낱말 속 labor에는 걸리지 않는다.
// '생산'·'노동'·'수출'·'수입'·'물가'처럼 흔한 낱말과 exports·imports는 뺐다.
const registryWords = [
  'production', 'consumption_investment', 'external_trade', 'labor',
  'prices_commodities', 'finance_housing', 'overseas',
  'all_industry_production', 'mining_manufacturing_production', 'service_production',
  'retail_sales_index', 'facility_investment_index', 'construction_completed_constant',
  'trade_balance', 'employed_persons', 'unemployment_rate', 'consumer_price_index',
  'dubai_crude', 'treasury_bond_3y', 'usd_krw_rate', 'housing_sale_price', 'g20_cli',
  '소비·투자', '대외거래', '물가·원자재', '금융·주택', '해외 경기',
  '전산업생산', '광공업생산', '서비스업생산', '소매판매액지수', '설비투자지수', '건설기성액',
  '무역수지', '취업자 수', '실업률', '소비자물가', '두바이유', '국고채 3년', '원/달러 환율',
  '주택 매매가격', 'G20 경기선행지수',
  '실물 경기', '대외 거래', '금융 여건',
]

function containsRegistryWord(source: string, word: string): boolean {
  if (!/^[\w ]+$/.test(word)) return source.includes(word)
  return new RegExp(`(?<![A-Za-z0-9_])${word}(?![A-Za-z0-9_])`, 'i').test(source)
}

describe('010 화면의 등록표 경계', () => {
  it('src를 훑어 API·타입·훅·화면·컴포넌트를 빠짐없이 검사한다', () => {
    expect(sources).toContain('src/pages/RegimeSearchPage.tsx')
    expect(sources).toContain('src/components/regime/CaseDetail.tsx')
    expect(sources).toContain('src/api/regime.ts')
    expect(sources).toContain('src/components/marketflow/FieldPastCompare.tsx')
    expect(sources).toContain('src/components/marketflow/CompositePast.tsx')
    expect(sources).toContain('src/components/marketflow/PastCaseChooser.tsx')
    expect(sources).toContain('src/components/marketflow/PastFootnotes.tsx')
    expect(sources.length).toBeGreaterThan(10)
  })

  it('지표와 분야의 등록표 ID·이름을 코드에 박지 않는다', () => {
    const found = sources.flatMap((path) => registryWords
      .filter((word) => containsRegistryWord(readFileSync(path, 'utf8'), word))
      .map((word) => `${path}: ${word}`))
    expect(found).toEqual([])
  })

  it('결과 설명이나 추천 판단 문구를 화면에서 만들지 않는다', () => {
    const forbidden = [/\d+%\s*유사/, /충분히 유사/, /대표 유사국면/, /경제환경이 좋아서/, /투자 성공 확률/]
    const found = sources.flatMap((path) => forbidden
      .filter((pattern) => pattern.test(readFileSync(path, 'utf8')))
      .map((pattern) => `${path}: ${pattern}`))
    expect(found).toEqual([])
  })
})

/** 주석은 검사 대상이 아니다 — 화면에 나가는 문자열만 본다. */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^[ \t]*\/\/.*$/gm, '')
}

const koreanSentence = /[가-힣][^'"`<>{}]*(습니다|입니다|합니다|됩니다)/g

describe('010 화면의 고정 문구 경계', () => {
  const screens = sources.filter((path) => path.endsWith('.tsx'))

  it('결과·상태·안내 영역의 문장을 화면 파일에서 만들지 않는다', () => {
    // 그 자리 문장은 API 응답에서 오고, 응답이 주지 않는 고정 문구는 fixedCopy.ts에만 둔다.
    const found = screens.flatMap((path) =>
      (stripComments(readFileSync(path, 'utf8')).match(koreanSentence) ?? []).map((text) => `${path}: ${text}`))
    expect(found).toEqual([])
  })

  it('고정 문구는 계약 근거와 함께 한 파일에 모인다', () => {
    const copy = readFileSync('src/components/regime/fixedCopy.ts', 'utf8')
    expect(copy.match(koreanSentence)?.length).toBeGreaterThan(5)
    // 근거 없는 문구가 조용히 늘지 않게 export마다 주석을 요구한다.
    const exports = copy.match(/^export const /gm) ?? []
    const documented = copy.match(/\*\/\nexport const /g) ?? []
    expect(documented.length).toBe(exports.length)
  })
})
