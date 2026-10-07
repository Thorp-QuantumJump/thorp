import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { ArticleStamp, type ArticleStampState } from './ArticleStamp'

/** 013 T137. 글 도장 다섯 모양(design-notes §4 상태 문법, §5 움직임, §6 접근성). */

const CSS = 'components/learning/me/ArticleStamp.module.css'
const STATES: ArticleStampState[] = ['done', 'reading', 'unread', 'preparing', 'neutral']

function stamp(state: ArticleStampState, extra: Partial<Parameters<typeof ArticleStamp>[0]> = {}) {
  const { container } = render(<ArticleStamp state={state} size={48} label="1-4" disc {...extra} />)
  return container.querySelector('svg')!
}

describe('ArticleStamp', () => {
  it('다섯 모양 모두 그림이라 보조 기술에 읽히지 않는다', () => {
    for (const state of STATES) {
      const svg = stamp(state)
      expect(svg.getAttribute('data-stamp')).toBe(state)
      expect(svg.getAttribute('aria-hidden')).toBe('true')
      expect(svg.getAttribute('focusable')).toBe('false')
    }
  })

  it("'준비 중'만 점선이고 '안 읽음'은 같은 굵기의 실선이다. 두 윤곽은 같은 색 규칙을 쓴다", () => {
    const outline = (svg: SVGSVGElement) => svg.querySelector('[data-part="outline"]')!
    const preparing = outline(stamp('preparing'))
    const unread = outline(stamp('unread'))
    expect(preparing.getAttribute('stroke-dasharray')).not.toBeNull()
    expect(unread.getAttribute('stroke-dasharray')).toBeNull()
    expect(preparing.getAttribute('stroke-width')).toBe(unread.getAttribute('stroke-width'))
    expect(preparing.getAttribute('class')).toBe(unread.getAttribute('class'))
  })

  it('중립 모양에는 윤곽선·진행 호·체크가 없다. 비로그인에게 아직 안 함으로 읽히지 않게 한다', () => {
    const svg = stamp('neutral')
    for (const part of ['outline', 'progress', 'ink', 'mark']) {
      expect(svg.querySelector(`[data-part="${part}"]`)).toBeNull()
    }
  })

  it('읽는 중은 읽은 비율만큼 진행 호를 채운다', () => {
    const arc = stamp('reading', { percent: 62 }).querySelector('[data-part="progress"]')!
    expect(arc.getAttribute('pathLength')).toBe('100')
    expect(arc.getAttribute('stroke-dasharray')).toBe('62 100')
  })

  it('안쪽 고리의 단계 장식 색만 --stamp-ring으로 넘긴다', () => {
    const svg = stamp('unread', { stageNumber: 1 })
    expect(svg.getAttribute('style')).toContain('--stamp-ring: #5FD3A6')
    expect(svg.querySelector('[data-part="ring"]')).not.toBeNull()
    expect(cssDeclarations(CSS, '.ring')).toMatch(/stroke:\s*var\(--stamp-ring, var\(--thorp-muted\)\)/)
  })

  it('색은 모두 의미 토큰이라 SVG 속성에 색 값을 두지 않는다(index.css, 디자인 전환을 따른다)', () => {
    for (const state of STATES) {
      const svg = stamp(state, { stageNumber: 2 })
      // 중립 모양의 눌린 홈(검정 그림자)만 색 속성을 쓴다. `fill="none"`은 색이 아니므로 센다.
      const colored = Array.from(svg.querySelectorAll('*')).filter((el) => {
        if (el.getAttribute('data-part') === 'groove') return false
        const fill = el.getAttribute('fill')
        return (fill !== null && fill !== 'none') || el.hasAttribute('stroke')
      })
      expect(colored).toEqual([])
    }
    expect(cssDeclarations(CSS, '.paper')).toMatch(/fill:\s*var\(--thorp-surface\)/)
    expect(cssDeclarations(CSS, ".stamp[data-stamp='neutral'] .paper")).toMatch(/var\(--thorp-surface-raised\)/)
    // 준비 중 표식의 원판은 바탕색이라 채운 원으로 보이지 않는다(목업 marker 'pend').
    expect(cssDeclarations(CSS, ".stamp[data-stamp='preparing'] .paper")).toMatch(/var\(--thorp-background\)/)
    expect(cssDeclarations(CSS, '.outline')).toMatch(/stroke:\s*var\(--thorp-muted\)/)
    expect(cssDeclarations(CSS, '.progress')).toMatch(/stroke:\s*var\(--thorp-accent-soft\)/)
    expect(cssDeclarations(CSS, '.ink')).toMatch(/var\(--thorp-accent-soft\)/)
  })

  it('같은 도장을 여러 번 그려도 필터 식별자가 겹치지 않는다(useId)', () => {
    const { container } = render(
      <>
        <ArticleStamp state="done" size={48} disc />
        <ArticleStamp state="done" size={40} disc />
      </>,
    )
    const ids = Array.from(container.querySelectorAll('filter')).map((filter) => filter.id)
    expect(ids).toHaveLength(2)
    expect(new Set(ids).size).toBe(2)
    for (const id of ids) expect(id).toMatch(/^[a-zA-Z0-9_-]+$/)
  })

  it('움직임은 줄이기 설정이 아닐 때만 있고, 강제 색 모드에서도 윤곽과 글자가 남는다', () => {
    expect(cssMediaDeclarations(CSS, '(prefers-reduced-motion: no-preference)', '.progress')).toMatch(
      /animation:\s*arc-fill 600ms/,
    )
    expect(cssDeclarations(CSS, '.stamp')).not.toMatch(/animation/)
    expect(cssMediaDeclarations(CSS, '(forced-colors: active)', '.mark')).toMatch(/stroke:\s*CanvasText/)
    expect(cssMediaDeclarations(CSS, '(forced-colors: active)', '.markText')).toMatch(/fill:\s*CanvasText/)
  })
})
