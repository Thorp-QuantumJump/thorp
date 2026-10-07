import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssDeclarations } from '@/test/cssRule'
import { releasedCurriculum } from '@/test/learningFixtures'
import { StageBand } from './StageBand'

/** 013 T137·T141. 단계 지역 띠(design-notes §3.1·§4, FR-031 표시 규칙). */

describe('StageBand', () => {
  it('공개된 단계는 번호·수준·이름·한 줄 설명과 글 수·예상 읽기 시간을 보인다', () => {
    render(<StageBand stage={releasedCurriculum.stages[0]} headingId="h1" />)

    expect(screen.getByRole('heading', { level: 3 }).textContent).toBe('1단계 · 입문항구 기초')
    expect(screen.getByText('합성 단계 설명 하나다.')).not.toBeNull()
    expect(screen.getByText('2편 · 약 9분')).not.toBeNull()
  })

  it('준비 중 단계는 준비 중과 사유만 있고 편수·날짜·잠금 어휘가 없다', () => {
    const stage = releasedCurriculum.stages[2]
    const { container } = render(<StageBand stage={stage} headingId="h3" />)

    expect(container.textContent).toContain('준비 중')
    expect(container.textContent).toContain('아직 공개되지 않은 단계입니다.')
    expect(container.textContent).toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    expect(container.textContent).not.toMatch(/\d+편|\d{4}-\d{2}-\d{2}|약 \d+분|잠김|해금|열립니다|공개 예정|곧 /)
    // 표식과 풍경은 점선 '설계 도면'이다. 그림은 보조 기술에 읽히지 않는다.
    expect(container.querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('preparing')
    expect(container.querySelector('[data-landscape]')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('준비 중 띠의 테두리는 점선이고 강제 색 모드에서도 선 모양이 남는다', () => {
    expect(cssDeclarations('components/learning/curriculum/StageBand.module.css', '.pending')).toMatch(
      /border:\s*1\.5px dashed/,
    )
    expect(
      cssDeclarations('components/learning/curriculum/StatusChips.module.css', '.preparing'),
    ).toMatch(/dashed/)
  })
})
