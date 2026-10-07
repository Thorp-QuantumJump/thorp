import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { releasedCurriculum } from '@/test/learningFixtures'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { StageView } from '@/types/learning'
import type { ArticleProgress } from './articleProgress'
import { JourneyMap } from './JourneyMap'

/** 013 T137·T141. 커리큘럼 보기: 여정 지도(design-notes §3.1·§4·§5, quickstart 8-4·11-6). */

/**
 * jsdom은 배치를 하지 않으므로 길이 재는 위치를 지어 준다. 지도는 800×2000이고 `data-road` 점은 문서 순서대로 100px씩
 * 아래에 놓인다.
 */
function fakeLayout() {
  const original = Element.prototype.getBoundingClientRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.querySelector('[data-road]') && this.querySelector('svg[data-road-layer], h2')) {
      return DOMRect.fromRect({ x: 0, y: 0, width: 800, height: 2000 })
    }
    if (this.hasAttribute('data-road')) {
      const points = Array.from(document.querySelectorAll('[data-road]'))
      return DOMRect.fromRect({ x: 52, y: 100 * points.indexOf(this), width: 0, height: 0 })
    }
    return original.call(this)
  })
}

describe('JourneyMap', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('공개된 단계는 띠와 글 노드, 공개 경계 뒤 준비 중 단계는 점선 띠로 단계 순서대로 그린다', () => {
    renderWithRouter(<JourneyMap stages={releasedCurriculum.stages} />)

    const headings = screen.getAllByRole('heading', { level: 3 })
    expect(headings.map((heading) => heading.textContent)).toEqual([
      '1단계 · 입문항구 기초',
      '2단계 · 입문너울과 위험',
      '3단계 · 입문~초급물때표 읽기준비 중',
      '4단계 · 초급해도와 비용준비 중',
      '5단계 · 초급암초 점검준비 중',
      '6단계 · 초급날씨와 바다준비 중',
    ])
    const boundary = screen.getByText('지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.')
    expect(headings[1].compareDocumentPosition(boundary) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(boundary.compareDocumentPosition(headings[2]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('노드와 카드가 한 링크이고, 상태를 모르면 상태 글자 없이 중립 도장이다', () => {
    renderWithRouter(<JourneyMap stages={releasedCurriculum.stages} />)

    const links = within(screen.getByRole('list', { name: '1단계 글' })).getAllByRole('link')
    expect(links).toHaveLength(2)
    const [first] = links
    expect(first.querySelectorAll('[data-stamp="neutral"]')).toHaveLength(2)
    expect(first.textContent).toContain('항구 기록')
    expect(first.textContent).toContain('약 5분')
    expect(first.textContent).not.toMatch(/완료|읽는 중|안 읽음/)
    // 그림은 보조 기술에 읽히지 않는다(design-notes §6).
    expect(first.querySelector('[data-road]')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('상태를 받으면 도장 모양과 상태 글자를 함께 보이고 단계 표식은 완주 여부를 따른다', () => {
    const progress = new Map<string, ArticleProgress>([
      ['harbor-notes', { state: 'done', masteredTermCount: 2 }],
      ['dock-fees', { state: 'done', masteredTermCount: 0 }],
      ['tide-reading', { state: 'reading', percent: 62, masteredTermCount: 0 }],
      ['swell-reading', { state: 'unread', masteredTermCount: 0 }],
    ])

    renderWithRouter(<JourneyMap stages={releasedCurriculum.stages} progress={progress} currentArticleId="tide-reading" />)

    const stageTwo = within(screen.getByRole('list', { name: '2단계 글' })).getAllByRole('link')
    expect(stageTwo[0].textContent).toContain('62% 읽는 중')
    expect(stageTwo[0].querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('reading')
    expect(stageTwo[1].textContent).toContain('안 읽음')
    const stageOne = screen.getByRole('region', { name: /항구 기초/ })
    expect(stageOne.querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('done')
    expect(within(stageOne).getAllByRole('link')[0].textContent).toContain('용어 2개 중 2개 익힘')
  })

  it('공개됐지만 글이 0편인 단계는 공개 경계 앞 제자리에서 점선 띠와 다시 준비한다는 사유로 보인다', () => {
    const stages: StageView[] = releasedCurriculum.stages.map((stage) =>
      stage.number === 2 ? { ...stage, status: 'REPREPARING', articles: [] } : stage,
    )

    renderWithRouter(<JourneyMap stages={stages} />)

    const region = screen.getByRole('region', { name: /너울과 위험/ })
    expect(region.textContent).toContain('준비 중')
    expect(region.textContent).toContain('이 단계의 글을 다시 준비하고 있습니다. 글이 게시되면 이 자리에 보입니다.')
    expect(region.textContent).not.toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    const boundary = screen.getByText('지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.')
    expect(region.compareDocumentPosition(boundary) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('길은 data-road 점을 이어 그리고 공개 경계 앞에서 끊어 끝 표시를 둔 뒤 점선으로 다시 시작한다', () => {
    fakeLayout()

    const { container } = renderWithRouter(<JourneyMap stages={releasedCurriculum.stages} />)

    const road = container.querySelector('svg[data-road-layer]')!
    expect(road.getAttribute('aria-hidden')).toBe('true')
    const paths = Array.from(road.querySelectorAll('path')).map((path) => path.getAttribute('class') ?? '')
    expect(paths.some((name) => /roadNeutral/.test(name))).toBe(true)
    expect(paths.some((name) => /roadPreparing/.test(name))).toBe(true)
    // 점선 길에는 그리는 움직임을 주지 않는다. 움직임은 읽는 길에만 한 번 있다.
    expect(paths.filter((name) => /roadPreparing/.test(name)).every((name) => !/draw/.test(name))).toBe(true)
    expect(road.querySelectorAll('circle')).toHaveLength(1)
  })

  it('첫 공개 단계가 글이 0편인 단계여도 그 점선 표식에서 다음 띠로 가는 구간이 선 없이 비지 않는다', () => {
    fakeLayout()
    const stages: StageView[] = releasedCurriculum.stages.map((stage) =>
      stage.number === 1 ? { ...stage, status: 'REPREPARING', articles: [] } : stage,
    )

    const { container } = renderWithRouter(<JourneyMap stages={stages} />)

    const road = container.querySelector('svg[data-road-layer]')!
    const segments = Array.from(road.querySelectorAll('path')).slice(1) // 첫 경로는 바닥(bed)이다
    expect(segments.length).toBeGreaterThan(0)
    for (const segment of segments) {
      expect(segment.getAttribute('class')).toMatch(/roadRead|roadTodo|roadNeutral|roadPreparing/)
    }
  })

  it("지금 이어 읽을 글의 카드에는 '이어서 읽기 →'를 둔다", () => {
    const progress = new Map<string, ArticleProgress>([
      ['harbor-notes', { state: 'done', masteredTermCount: 2 }],
      ['dock-fees', { state: 'reading', percent: 40, masteredTermCount: 0 }],
      ['tide-reading', { state: 'unread', masteredTermCount: 0 }],
      ['swell-reading', { state: 'unread', masteredTermCount: 0 }],
    ])

    renderWithRouter(<JourneyMap stages={releasedCurriculum.stages} progress={progress} currentArticleId="dock-fees" />)

    const [first, second] = within(screen.getByRole('list', { name: '1단계 글' })).getAllByRole('link')
    expect(second.textContent).toContain('이어서 읽기')
    expect(second.querySelector('[data-tip]')).not.toBeNull()
    expect(first.textContent).not.toContain('이어서 읽기')
  })

  it('준비 중 단계로 가는 길만 점선이고, 길을 그리는 700ms 움직임은 움직임 줄이기 설정이 아닐 때만 있다', () => {
    const path = 'components/learning/curriculum/JourneyMap.module.css'
    expect(cssDeclarations(path, '.road .roadPreparing')).toMatch(/stroke-dasharray:\s*6 6/)
    for (const solid of ['.roadRead', '.roadTodo', '.roadNeutral']) {
      expect(cssDeclarations(path, solid)).not.toMatch(/dasharray/)
    }
    expect(cssMediaDeclarations(path, '(prefers-reduced-motion: no-preference)', '.road .draw')).toMatch(
      /animation:\s*draw-path 700ms/,
    )
    // 움직임 규칙이 블록 밖(기본 모습)에 새어 나오지 않는다. 기본은 최종 모습이다(design-notes §5).
    const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'JourneyMap.module.css'), 'utf-8')
    const outside = css.replace(/@media \(prefers-reduced-motion: no-preference\) \{[\s\S]*?\n\}/, '')
    expect(outside).not.toMatch(/animation:/)
  })
})
