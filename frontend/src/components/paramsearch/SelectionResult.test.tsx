import { readFileSync } from 'node:fs'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  baselineFixture,
  completedCandidate,
  configFixture,
  noNeighborCandidate,
  selectionFixture,
} from '@/test/paramSearchFixtures'
import { SelectionResult } from './SelectionResult'

/** 결과를 그리는 007 화면 코드. 여기에 순위·추천 표현이 들어가면 안 된다. */
const resultSources = [
  'src/components/paramsearch/SelectionResult.tsx',
  'src/components/paramsearch/CandidateSummaryCard.tsx',
  'src/components/paramsearch/CandidateDetail.tsx',
  'src/components/paramsearch/CandidateTable.tsx',
  'src/components/paramsearch/CompareHandoff.tsx',
  'src/utils/paramSearchLabels.ts',
]

/**
 * 쓰는 순간 순위·추천이 되는 낱말. **"순위"는 여기 없다** — 우리 화면은 "순위가 아닙니다"라고
 * 말해야 해서 그 낱말 자체는 나타난다. 대신 아래 `순위`는 부정문일 때만 허용한다고 따로 본다.
 */
const rankingWords = [
  '1위',
  '2위',
  '3위',
  '랭킹',
  '추천',
  '최적',
  '최고',
  '우승',
  '베스트',
  'Best',
  'TOP',
]

/**
 * "순위"가 부정 없이 쓰였는가. 부정문이면 제약을 어기는 것이 아니라 밝히는 것이다.
 *
 * `아니`와 `아닙`을 모두 본다 — 한글은 음절 단위라 "아닙니다"에 "아니"가 들어 있지 않다.
 * 창을 넉넉히 잡는 것은 소스에서 줄바꿈이 사이에 끼기 때문이다.
 */
function rankingClaimedAsFact(text: string): string[] {
  return [...text.matchAll(/순위/g)]
    .map((match) => text.slice(match.index, match.index + 30))
    .filter((window) => !/순위\s*(가|는|를|도)?\s*(아니|아닙)/.test(window))
}

/**
 * 주석을 걷어낸 소스. 왜 순위를 만들지 않는지 적은 주석까지 위반으로 세면 설명을 지우게 된다 —
 * 지켜야 할 것은 <b>화면에 닿는 문자열</b>이다.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function renderResult(overrides: Partial<Parameters<typeof SelectionResult>[0]> = {}) {
  return render(
    <SelectionResult
      selection={selectionFixture}
      selectionRule={configFixture.selectionRule}
      baseline={baselineFixture}
      config={configFixture}
      candidates={[completedCandidate, noNeighborCandidate]}
      onOpenDetail={vi.fn()}
      comparePicks={[]}
      onCompareToggle={vi.fn()}
      {...overrides}
    />,
  )
}

/**
 * 표시 후보는 순위표가 아니다(T088, SC-006, 원칙 III).
 *
 * <p>선정이 "필터를 통과한 후보를 비용 차감 후 수익으로 정렬한 상위 5개"라서 <b>UI에서 순위로
 * 읽히기 쉽다</b>. plan의 설계 제약은 그 위험 때문에 붙은 것이고, 이 테스트가 그 제약을 지킨다.
 *
 * <p>화면 문구만 보지 않고 <b>소스도 읽는다</b> — 조건부로만 나타나는 배지는 렌더 테스트를
 * 빠져나간다.
 */
describe('SelectionResult', () => {
  it('순위 번호·추천·우승 표현이 0건이다', () => {
    const { container } = renderResult()
    const text = container.textContent ?? ''

    for (const word of rankingWords) {
      expect(text, `"${word}" 는 순위·추천으로 읽힌다`).not.toContain(word)
    }
    expect(rankingClaimedAsFact(text), '"순위"는 부정문에서만 쓴다').toEqual([])
  })

  it('번호가 붙는 목록으로 그리지 않는다', () => {
    const { container } = renderResult()

    // ol 은 번호를 만든다. 번호가 생기는 순간 1위가 생긴다.
    expect(container.querySelectorAll('ol')).toHaveLength(0)
    expect(container.querySelectorAll('ul').length).toBeGreaterThan(0)
  })

  it('무엇으로 정렬했는지 이름으로 밝힌다', () => {
    const { container } = renderResult()

    expect(container.textContent).toContain('비용 차감 후 수익 기준으로 정렬한 집합입니다')
  })

  it('충족 후보가 모자라면 그대로 적고 기준을 낮추지 않는다고 말한다', () => {
    const { container } = renderResult()

    expect(container.textContent).toContain('기준을 충족한 후보가 2개입니다')
    expect(container.textContent).toContain('기준을 낮춰 개수를 채우지 않습니다')
  })

  it('손실 제약을 걸지 않았으면 그 사실을 적는다', () => {
    const { container } = renderResult()

    expect(container.textContent).toContain('손실 제약은 걸지 않았습니다')
  })

  it('손실 제약을 걸었으면 무엇을 기준으로 삼았는지 적는다', () => {
    const { container } = renderResult({
      selection: { ...selectionFixture, drawdownFilterApplied: true },
    })

    expect(container.textContent).toContain('손실 제약 기준은 캐리 순환입니다')
    expect(container.textContent).toContain('-20.00%')
  })

  it('후보마다 검증 표시를 함께 보인다', () => {
    renderResult()

    expect(screen.getAllByText('미검증')).toHaveLength(2)
  })

  it('충족한 후보가 없으면 빈 목록 대신 기준을 낮추지 않는다고 말한다', () => {
    const { container } = renderResult({
      selection: { ...selectionFixture, displayed: [], note: null },
      candidates: [],
    })

    expect(container.textContent).toContain('기준을 충족한 설정이 없습니다')
    expect(container.textContent).toContain('기준을 낮춰 채우지 않으므로')
  })

  it('결과 화면 코드 어디에도 순위·추천 낱말이 없다', () => {
    const violations: string[] = []

    for (const source of resultSources) {
      const content = withoutComments(readFileSync(source, 'utf-8'))
      for (const word of rankingWords) {
        if (content.includes(word)) violations.push(`${source} → ${word}`)
      }
      for (const claim of rankingClaimedAsFact(content)) {
        violations.push(`${source} → ${claim}`)
      }
    }

    expect(violations).toEqual([])
  })
})
