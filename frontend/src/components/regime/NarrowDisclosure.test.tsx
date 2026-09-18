import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('좁은 화면의 신뢰 범위 표시', () => {
  it('다섯 표시를 접거나 자르지 않고 한 열로 재배치한다', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/components/regime/Disclosure.module.css'), 'utf8')
    expect(css).toMatch(/@media\s*\(max-width:\s*520px\)\s*\{\s*\.disclosure\s*\{\s*grid-template-columns:\s*1fr/)
    expect(css).toMatch(/overflow-wrap:\s*anywhere/)
    expect(css).not.toMatch(/display:\s*none|visibility:\s*hidden|overflow:\s*hidden/)
  })

  it('다섯 표시를 담는 자리도 좁아졌다고 감추지 않는다', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/pages/RegimeSearchPage.module.css'), 'utf8')
    const container = css.match(/\.disclosures\s*\{[^}]*\}/g) ?? []
    expect(container.length).toBeGreaterThan(0)
    for (const rule of container) {
      expect(rule).not.toMatch(/display:\s*none|visibility:\s*hidden|overflow:\s*hidden|max-height/)
    }
  })
})
