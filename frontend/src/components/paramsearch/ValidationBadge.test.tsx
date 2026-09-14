import { readFileSync } from 'node:fs'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  baselineFixture,
  completedCandidate,
  configFixture,
  excludedCandidate,
  noNeighborCandidate,
  notInDisplayCandidate,
  selectionFixture,
} from '@/test/paramSearchFixtures'
import type { CandidatePage } from '@/types/paramSearch'
import { CandidateDetail } from './CandidateDetail'
import { CandidateSummaryCard } from './CandidateSummaryCard'
import { CandidateTable } from './CandidateTable'
import { SelectionResult } from './SelectionResult'
import { ValidationBadge } from './ValidationBadge'

const apiMocks = vi.hoisted(() => ({
  getParamSearchCandidates: vi.fn(),
}))

vi.mock('@/api/paramSearch', async () => {
  const actual = await vi.importActual<typeof import('@/api/paramSearch')>('@/api/paramSearch')
  return { ...actual, ...apiMocks }
})

/** 후보가 보이는 007 화면. 여기 어디에도 배지 없이 후보를 그리는 곳이 있으면 안 된다. */
const candidateSurfaces = [
  'src/components/paramsearch/CandidateSummaryCard.tsx',
  'src/components/paramsearch/CandidateDetail.tsx',
  'src/components/paramsearch/CandidateTable.tsx',
  'src/components/paramsearch/SelectionResult.tsx',
  'src/components/paramsearch/PreflightPanel.tsx',
  'src/pages/ParamSearchPage.tsx',
]

/** 배지는 `data-label`로 찾는다 — CSS Module은 클래스 이름을 바꾸지만 속성은 그대로다. */
function badges(container: HTMLElement) {
  return container.querySelectorAll('[data-label]')
}

/**
 * 검증 배지가 빠진 곳이 없는지(T116, plan 설계 제약 2, FR-015).
 *
 * <p><b>빠짐은 조용하다.</b> 배지가 없어도 화면은 멀쩡해 보이고 숫자도 다 나온다. 그래서 이
 * 테스트는 "배지가 잘 그려지는가"가 아니라 <b>"후보를 그리는 곳마다 배지가 하나씩 있는가"</b>를
 * 센다.
 */
describe('ValidationBadge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('세 라벨을 각각 읽을 수 있는 말로 보인다', () => {
    const { container } = render(
      <>
        <ValidationBadge label="UNVALIDATED" />
        <ValidationBadge label="SEARCH_RESULT" />
        <ValidationBadge label="HELD_OUT_RESULT" />
      </>,
    )

    expect(container.textContent).toContain('미검증')
    expect(container.textContent).toContain('탐색 구간 결과')
    expect(container.textContent).toContain('검증 구간 결과')
    expect(badges(container)).toHaveLength(3)
  })

  it('색이 아니라 글자가 뜻을 진다', () => {
    const { container } = render(<ValidationBadge label="UNVALIDATED" />)
    const badge = container.querySelector('[data-label]')

    // data-label 은 스타일을 가르는 열쇠일 뿐이고, 읽히는 것은 안의 글자다.
    expect(badge?.getAttribute('data-label')).toBe('UNVALIDATED')
    expect(badge?.textContent).toContain('미검증')
  })

  it('무엇에 붙은 배지인지 보조기술에 알린다', () => {
    render(<ValidationBadge label="UNVALIDATED" describes="알파 기울기 5거래일" />)

    expect(screen.getByText(/알파 기울기 5거래일/)).toBeTruthy()
  })

  it('표시 후보 집합은 후보 수만큼 배지를 낸다', () => {
    const { container } = render(
      <SelectionResult
        selection={selectionFixture}
        selectionRule={configFixture.selectionRule}
        baseline={baselineFixture}
        config={configFixture}
        candidates={[completedCandidate, noNeighborCandidate]}
        onOpenDetail={vi.fn()}
        comparePicks={[]}
        onCompareToggle={vi.fn()}
      />,
    )

    expect(badges(container)).toHaveLength(2)
  })

  it('후보 카드와 상세에도 각각 하나씩 있다', () => {
    const card = render(
      <CandidateSummaryCard candidate={completedCandidate} config={configFixture} />,
    )
    expect(badges(card.container)).toHaveLength(1)
    card.unmount()

    const detail = render(
      <CandidateDetail
        candidate={excludedCandidate}
        config={configFixture}
        selectionRule={configFixture.selectionRule}
        onClose={vi.fn()}
      />,
    )
    expect(badges(detail.container)).toHaveLength(1)
  })

  it('전체 후보 표는 줄마다 배지를 낸다', async () => {
    const page: CandidatePage = {
      page: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
      content: [completedCandidate, excludedCandidate, notInDisplayCandidate],
    }
    apiMocks.getParamSearchCandidates.mockResolvedValue(page)

    const { container } = render(
      <CandidateTable
        searchId="PS-20260914-000001"
        config={configFixture}
        onOpenDetail={vi.fn()}
        comparePicks={[]}
        onCompareToggle={vi.fn()}
      />,
    )

    // 계산 전 제외 후보에도 붙는다 — 성과가 없는 것과 검증을 말할 수 없는 것은 다른 이야기다.
    await waitFor(() => expect(badges(container)).toHaveLength(3))
  })

  it('후보를 그리는 화면이 라벨을 직접 그리지 않는다', () => {
    const violations: string[] = []

    for (const source of candidateSurfaces) {
      const content = readFileSync(source, 'utf-8')
      if (content.includes('validationLabelText(')) {
        violations.push(`${source} → validationLabelText 직접 호출`)
      }
    }

    // 라벨을 각자 그리면 어디에 빠졌는지 셀 수 없게 된다.
    expect(violations).toEqual([])
  })
})
