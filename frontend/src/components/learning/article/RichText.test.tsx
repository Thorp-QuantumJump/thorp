import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { Run } from '@/types/learning'
import { RichText, SourceLink } from './RichText'

/** 013 T134. '(*출처)' 링크의 속성과 읽는 이름, 조각을 글자로만 그리는지 본다(FR-007, FR-026). */
describe('SourceLink', () => {
  it('새 탭에서 열고 opener를 넘기지 않으며 출처 이름과 외부 링크임을 읽는다', () => {
    render(<SourceLink source={{ label: '합성 항만 자료', url: 'https://example.com/harbor' }} />)

    const link = screen.getByRole('link', { name: /합성 항만 자료/ })
    expect(link.getAttribute('href')).toBe('https://example.com/harbor')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(link.getAttribute('aria-label')).toBe('출처: 합성 항만 자료 (외부 링크, 새 탭에서 열림)')
    // 보이는 글자는 '(*출처)'이고, 출처 이름은 마우스·초점에 뜨는 자리에 있다.
    expect(link.textContent).toContain('(*출처)')
    expect(link.textContent).toContain('합성 항만 자료')
  })
})

describe('RichText', () => {
  it('인용한 글자는 링크가 아니고 그 옆의 표시만 링크다', () => {
    const runs: Run[] = [
      { text: '인용한 문장' },
      { source: { label: '합성 자료', url: 'https://example.com/a' } },
    ]
    render(
      <p data-testid="para">
        <RichText runs={runs} blockIndex={0} field="content" />
      </p>,
    )

    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0].textContent).not.toContain('인용한 문장')
    expect(screen.getByTestId('para').textContent?.startsWith('인용한 문장(*출처)')).toBe(true)
  })

  it('태그 문자열을 태그로 해석하지 않는다', () => {
    const runs: Run[] = [
      { text: '앞 <b>강조</b> 뒤 ' },
      { term: 'x-term', text: '<img src=x onerror=alert(1)>', owned: true, aliases: [] },
    ]
    const { container } = render(
      <MemoryRouter>
        <RichText runs={runs} blockIndex={0} field="content" />
      </MemoryRouter>,
    )

    expect(container.textContent).toContain('앞 <b>강조</b> 뒤 ')
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
  })
})
