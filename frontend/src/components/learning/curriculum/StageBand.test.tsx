import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { releasedCurriculum } from '@/test/learningFixtures'
import type { StageView } from '@/types/learning'
import { StageBand } from './StageBand'

/** 013 T137·T141. 단계 지역 띠(design-notes §3.1·§4·§6, FR-031 표시 규칙). */

const CSS = 'components/learning/curriculum/StageBand.module.css'

describe('StageBand', () => {
  it('공개된 단계는 번호·수준·이름·한 줄 설명과 글 수·예상 읽기 시간을 보인다', () => {
    const { container } = render(<StageBand stage={releasedCurriculum.stages[0]} headingId="h1" />)

    expect(screen.getByRole('heading', { level: 3 }).textContent).toBe('1단계 · 입문항구 기초')
    expect(screen.getByText('합성 단계 설명 하나다.')).not.toBeNull()
    expect(screen.getByText('2편 · 약 9분')).not.toBeNull()
    // 공개된 띠만 '○단계 보기'가 짚는 자리다. 상태를 모르면 표식은 중립 원판이다.
    expect(container.querySelector('[data-stage-band]')).not.toBeNull()
    expect(container.querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('neutral')
  })

  it('준비 중 단계는 점선 띠에 준비 중과 사유만 있고 편수·날짜·잠금 어휘가 없다', () => {
    const stage = releasedCurriculum.stages[2]
    const { container } = render(<StageBand stage={stage} headingId="h3" />)

    expect(container.textContent).toContain('준비 중')
    expect(container.textContent).toContain('아직 공개되지 않은 단계입니다.')
    expect(container.textContent).toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    expect(container.textContent).not.toMatch(/\d+편|\d{4}-\d{2}-\d{2}|약 \d+분|잠김|해금|열립니다|공개 예정|곧 /)
    // 띠는 점선 '설계 도면' 모양을 받는다. 이름만 보면 규칙이 지워져도 통과하므로 규칙의 선언도 함께 본다.
    const band = screen.getByRole('heading', { level: 3 }).closest('div[style]') as HTMLElement
    expect(band.className).toMatch(/pending/)
    expect(cssDeclarations(CSS, '.pending')).toMatch(/border:\s*1\.5px dashed/)
    expect(band.hasAttribute('data-stage-band')).toBe(false)
    // 표식과 풍경도 점선이고, 그림은 보조 기술에 읽히지 않는다.
    expect(container.querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('preparing')
    expect(container.querySelector('[data-landscape]')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('글이 0편인 공개 단계는 다시 준비한다는 사유이고 공개되지 않았다는 화면 낭독 문장이 없다', () => {
    const stage: StageView = { ...releasedCurriculum.stages[1], status: 'REPREPARING', articles: [] }
    const { container } = render(<StageBand stage={stage} headingId="h2" />)

    expect(container.textContent).toContain('이 단계의 글을 다시 준비하고 있습니다. 글이 게시되면 이 자리에 보입니다.')
    expect(container.textContent).not.toContain('아직 공개되지 않은 단계입니다.')
    expect(container.textContent).not.toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
  })

  it('강제 색 모드에서도 준비 중 띠와 꼬리표의 점선이 시스템 글자색으로 남는다', () => {
    expect(cssMediaDeclarations(CSS, '(forced-colors: active)', '.pending')).toMatch(/border-color:\s*CanvasText/)
    expect(
      cssMediaDeclarations('components/learning/curriculum/StatusChips.module.css', '(forced-colors: active)', '.preparing'),
    ).toMatch(/border-color:\s*CanvasText/)
    expect(cssDeclarations('components/learning/curriculum/StatusChips.module.css', '.preparing')).toMatch(/dashed/)
  })

  it('상태를 아는 공개 단계의 표식은 실선 칸에 단계 색 안쪽 고리, 번호는 본문 글자색이다', () => {
    const { container } = render(<StageBand stage={releasedCurriculum.stages[0]} headingId="h1" markerState="unread" />)

    const stamp = container.querySelector('[data-stamp]')!
    expect(stamp.getAttribute('data-stamp')).toBe('unread')
    // 안쪽 고리는 1단계 장식 색(민트)이다. 상태를 나타내지 않는 꾸밈이다(design-notes §4).
    expect(stamp.querySelector('circle[stroke="#5FD3A6"]')).not.toBeNull()
    expect(stamp.closest('span')?.className).toMatch(/markerTracked/)
    expect(cssDeclarations(CSS, '.markerTracked')).toMatch(/color:\s*var\(--thorp-foreground\)/)
  })

  it('단계 자리로 옮겨 와 초점을 받는 제목은 2px 초록 외곽선이다(design-notes §6)', () => {
    render(<StageBand stage={releasedCurriculum.stages[0]} headingId="h1" />)

    expect(screen.getByRole('heading', { level: 3 }).getAttribute('tabindex')).toBe('-1')
    expect(cssDeclarations(CSS, '.heading:focus-visible')).toMatch(/outline:\s*var\(--thorp-focus-outline-compact\)/)
  })
})
