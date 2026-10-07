import { afterEach, describe, expect, it, vi } from 'vitest'
import { moveToStage, readRoadPoints, roadPointProps, stageAnchorProps, stageBandProps, stageHeadingProps } from './stageAnchors'

/** 013 T137·T139·T203. 커리큘럼 화면의 DOM 표지 규약을 이 모듈 하나가 만든다. */

function element(tag: string, attributes: Record<string, string | number | undefined>): HTMLElement {
  const el = document.createElement(tag)
  for (const [name, value] of Object.entries(attributes)) {
    if (value !== undefined) el.setAttribute(name, String(value))
  }
  return el
}

describe('stageAnchors', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('길 점을 문서 순서대로 읽고 모르는 종류는 중립 길로 본다', () => {
    const map = document.createElement('div')
    map.append(
      element('i', roadPointProps('start')),
      element('i', roadPointProps('read')),
      element('i', { 'data-road': 'g' }),
      element('i', roadPointProps('preparing', true)),
    )

    expect(readRoadPoints(map).map(({ kind, gap }) => ({ kind, gap }))).toEqual([
      { kind: 'start', gap: false },
      { kind: 'read', gap: false },
      { kind: 'neutral', gap: false },
      { kind: 'preparing', gap: true },
    ])
  })

  it('단계 자리로 옮기면 그 자리의 제목에 초점을 두고, 움직임 줄이기가 아니면 띠를 짚는다', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(prefers-reduced-motion: no-preference)' }))
    const anchor = element('section', stageAnchorProps(3))
    const band = element('div', stageBandProps)
    const heading = element('h3', { ...stageHeadingProps, tabindex: -1 })
    band.append(heading)
    anchor.append(band)
    document.body.append(anchor)

    moveToStage(3)

    expect(document.activeElement).toBe(heading)
    expect(band.hasAttribute('data-flash')).toBe(true)
  })

  it('움직임 줄이기 설정이면 띠를 짚지 않고, 없는 단계면 아무것도 하지 않는다', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(prefers-reduced-motion: reduce)' }))
    const anchor = element('section', stageAnchorProps(1))
    const band = element('div', stageBandProps)
    anchor.append(band)
    document.body.append(anchor)

    moveToStage(1)
    expect(band.hasAttribute('data-flash')).toBe(false)

    expect(() => moveToStage(9)).not.toThrow()
  })
})
