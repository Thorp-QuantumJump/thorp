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

  it("'준비 중'만 점선이고 '안 읽음'은 같은 색·같은 굵기의 실선이다", () => {
    const outline = (svg: SVGSVGElement) => svg.querySelector('circle[r="20"]')!
    const preparing = outline(stamp('preparing'))
    const unread = outline(stamp('unread'))
    expect(preparing.getAttribute('stroke-dasharray')).not.toBeNull()
    expect(unread.getAttribute('stroke-dasharray')).toBeNull()
    expect(preparing.getAttribute('stroke')).toBe(unread.getAttribute('stroke'))
    expect(preparing.getAttribute('stroke-width')).toBe(unread.getAttribute('stroke-width'))
  })

  it('중립 모양에는 윤곽선·진행 호·체크가 없다. 비로그인에게 아직 안 함으로 읽히지 않게 한다', () => {
    const svg = stamp('neutral')
    expect(svg.querySelector('[stroke="#8B928E"]')).toBeNull()
    expect(svg.querySelector('[stroke="#2FDC7A"]')).toBeNull()
    expect(svg.querySelector('path')).toBeNull()
  })

  it('읽는 중은 읽은 비율만큼 진행 호를 채운다', () => {
    const arc = stamp('reading', { percent: 62 }).querySelector('circle[stroke="#2FDC7A"]')!
    expect(arc.getAttribute('pathLength')).toBe('100')
    expect(arc.getAttribute('stroke-dasharray')).toBe('62 100')
  })

  it('준비 중 표식의 종이 원판은 바탕색이라 채운 원으로 보이지 않는다', () => {
    expect(stamp('preparing').querySelector('circle[r="21.3"]')?.getAttribute('fill')).toBe('#0D0F0E')
    expect(stamp('unread').querySelector('circle[r="21.3"]')?.getAttribute('fill')).toBe('#161918')
  })

  it('같은 도장을 여러 번 그려도 필터 식별자가 겹치지 않는다(useId)', () => {
    const { container } = render(
      <>
        <ArticleStamp state="done" size={48} disc />
        <ArticleStamp state="done" size={40} disc />
        <ArticleStamp state="reading" size={48} percent={50} tip />
        <ArticleStamp state="reading" size={40} percent={50} tip />
      </>,
    )
    const ids = Array.from(container.querySelectorAll('filter')).map((filter) => filter.id)
    expect(ids.length).toBe(6)
    expect(new Set(ids).size).toBe(ids.length)
    for (const id of ids) expect(id).toMatch(/^[a-zA-Z0-9_-]+$/)
  })

  it('방금 찍힌 도장은 속을 진하게 채우고 바깥에 후광 한 겹을 둔다(완료 카드, B13)', () => {
    const fresh = stamp('done', { fresh: true })
    const usual = stamp('done')
    expect(fresh.hasAttribute('data-fresh')).toBe(true)
    expect(fresh.querySelector('circle[r="23.5"]')).not.toBeNull()
    expect(usual.querySelector('circle[r="23.5"]')).toBeNull()
    expect(fresh.querySelector('circle[r="20"]')?.getAttribute('fill-opacity')).toBe('0.45')
  })

  it('움직임은 줄이기 설정이 아닐 때만 있고, 강제 색 모드에서도 윤곽이 남는다', () => {
    expect(cssMediaDeclarations(CSS, '(prefers-reduced-motion: no-preference)', '.progress')).toMatch(
      /animation:\s*arc-fill 600ms/,
    )
    expect(cssDeclarations(CSS, '.stamp')).not.toMatch(/animation/)
    expect(cssMediaDeclarations(CSS, '(forced-colors: active)', '.ink')).toMatch(/stroke:\s*CanvasText/)
  })
})
