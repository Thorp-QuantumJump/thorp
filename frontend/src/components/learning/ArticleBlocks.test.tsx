import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ArticleBlock } from '@/types/learning'
import { tideArticle } from '@/test/learningFixtures'
import { ArticleBlocks } from './ArticleBlocks'

/** 013 T027. 블록을 텍스트로만, 받은 그대로 그리는지 본다(research R3, FR-005). */
describe('ArticleBlocks', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('heading 블록은 h2다', () => {
    render(<ArticleBlocks blocks={tideArticle.blocks} />)

    expect(screen.getByRole('heading', { level: 2, name: '물이 가장 낮았던 때' })).not.toBeNull()
  })

  it('term 블록은 용어 이름과 문장을 받은 그대로 보인다', () => {
    const { container } = render(<ArticleBlocks blocks={tideArticle.blocks} />)

    const term = container.querySelector('dl')
    expect(term).not.toBeNull()
    expect(term?.querySelector('dt')?.textContent).toBe('썰물 간격')
    expect(term?.querySelector('dd')?.textContent).toBe(
      '합성 용어 문장이다. 줄이거나 바꾸지 않고 보여야 한다.',
    )
  })

  it("hypothetical 블록에는 '가상의 계산 예' 표시가 보인다", () => {
    render(<ArticleBlocks blocks={tideArticle.blocks} />)

    const aside = screen.getByRole('complementary', { name: '가상의 계산 예' })
    expect(within(aside).getByText('가상의 계산 예')).not.toBeNull()
    expect(within(aside).getByText(/3칸 들어왔다가 1칸 빠졌다/)).not.toBeNull()
  })

  it('문장 안의 태그 문자열을 태그로 해석하지 않는다', () => {
    const blocks: ArticleBlock[] = [
      { type: 'paragraph', text: '앞 <b>강조</b> 뒤' },
      { type: 'hypothetical', text: '<img src=x onerror=alert(1)>' },
    ]
    const { container } = render(<ArticleBlocks blocks={blocks} />)

    expect(screen.getByText('앞 <b>강조</b> 뒤')).not.toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
  })

  it('모르는 블록 종류는 그리지 않고 나머지는 그린다', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const blocks = [
      { type: 'chart', text: '그리면 안 되는 문장' },
      { type: 'paragraph', text: '보여야 하는 문단' },
    ] as unknown as ArticleBlock[]

    render(<ArticleBlocks blocks={blocks} />)

    expect(screen.queryByText('그리면 안 되는 문장')).toBeNull()
    expect(screen.getByText('보여야 하는 문단')).not.toBeNull()
  })
})
