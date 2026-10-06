import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { CurrentArticleBlock, LegacyArticleBlock } from '@/types/learning'
import { currentArticle, tideArticle } from '@/test/learningFixtures'
import { ArticleBody } from './ArticleBody'

/**
 * 013 T134. 블록마다 표시와 이름표를 그리고, 텍스트로만 받은 그대로 그리는지 본다(research R3, FR-005,
 * FR-026, quickstart 9-1·9-2). 1판 `ArticleBlocks.test.tsx`의 단언을 이어받는다.
 */

beforeAll(() => {
  // 글 안 도움말(Popover)의 위치 계산이 크기 관찰을 쓴다. jsdom에는 없다.
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

afterEach(() => {
  vi.restoreAllMocks()
})

function renderLegacy(blocks: LegacyArticleBlock[]) {
  return render(<ArticleBody article={{ edition: 'PRE_RELEASE', blocks }} />)
}

function renderCurrent(blocks: CurrentArticleBlock[] = currentArticle.blocks) {
  return render(
    <MemoryRouter>
      <ArticleBody article={{ edition: 'CURRENT', blocks }} />
    </MemoryRouter>,
  )
}

describe('ArticleBody — 9월 판', () => {
  it('heading은 h2, term은 이름과 문장, hypothetical은 가상의 계산 예 표시다', () => {
    const { container } = renderLegacy(tideArticle.blocks)

    expect(screen.getByRole('heading', { level: 2, name: '물이 가장 낮았던 때' })).not.toBeNull()
    const term = container.querySelector('dl')
    expect(term?.querySelector('dt')?.textContent).toBe('썰물 간격')
    expect(term?.querySelector('dd')?.textContent).toBe(
      '합성 용어 문장이다. 줄이거나 바꾸지 않고 보여야 한다.',
    )
    const aside = screen.getByRole('complementary', { name: '가상의 계산 예' })
    expect(within(aside).getByText(/3칸 들어왔다가 1칸 빠졌다/)).not.toBeNull()
  })

  it('문장 안의 태그 문자열을 태그로 해석하지 않는다', () => {
    const { container } = renderLegacy([
      { type: 'paragraph', text: '앞 <b>강조</b> 뒤' },
      { type: 'hypothetical', text: '<img src=x onerror=alert(1)>' },
    ])

    expect(screen.getByText('앞 <b>강조</b> 뒤')).not.toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
  })

  it('모르는 블록 종류는 그리지 않고 나머지는 그린다', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    renderLegacy([
      { type: 'chart', text: '그리면 안 되는 문장' },
      { type: 'paragraph', text: '보여야 하는 문단' },
    ] as unknown as LegacyArticleBlock[])

    expect(screen.queryByText('그리면 안 되는 문장')).toBeNull()
    expect(screen.getByText('보여야 하는 문단')).not.toBeNull()
  })

  it('마지막 블록 바로 뒤에 보이지 않는 본문 끝 표지가 있다(9월 판은 지금 모습 그대로)', () => {
    const { container } = renderLegacy(tideArticle.blocks)

    const end = container.querySelector('[data-article-end]')
    expect(end).not.toBeNull()
    expect(end?.previousElementSibling?.getAttribute('aria-label')).toBe('가상의 계산 예')
    expect(end?.textContent).toBe('')
    expect(end?.getAttribute('aria-hidden')).toBe('true')
  })
})

describe('ArticleBody — 새 기준', () => {
  it('핵심 요약은 세 줄 목록이다', () => {
    renderCurrent()

    const summary = screen.getByRole('region', { name: '핵심 요약' })
    expect(within(summary).getAllByRole('listitem')).toHaveLength(3)
  })

  it('용어 블록은 뜻·읽는 법 이름표와 주의 이름을 붙인 ⚠️ 한계 문장이다', () => {
    renderCurrent()

    const term = screen.getByRole('region', { name: '용어 너울 높이' })
    expect(within(term).getByText('뜻').tagName).toBe('DT')
    expect(within(term).getByText('합성 뜻 문장이다.').tagName).toBe('DD')
    expect(within(term).getByText('읽는 법').tagName).toBe('DT')
    expect(within(term).getByText('합성 읽는 법 문장이다.')).not.toBeNull()
    expect(within(term).getByRole('img', { name: '주의' }).textContent).toBe('⚠️')
    expect(within(term).getByText(/합성 한계 문장이다/)).not.toBeNull()
  })

  it('오해 상자에는 오해와 가상의 예 표시가, 비교 표에는 열 머리와 가상의 예 표시가 있다', () => {
    renderCurrent()

    const misconception = screen.getByRole('complementary', { name: '자주 하는 오해' })
    expect(within(misconception).getByText('오해')).not.toBeNull()
    expect(within(misconception).getByText('가상의 예')).not.toBeNull()

    const table = screen.getByRole('table')
    const headers = within(table).getAllByRole('columnheader')
    expect(headers.map((header) => header.textContent)).toEqual(['구분', '가 항구', '나 항구'])
    expect(headers.every((header) => header.getAttribute('scope') === 'col')).toBe(true)
    expect(within(table).getByText('가상의 예')).not.toBeNull()
  })

  it('비교 표의 각 행 첫 칸은 행 머리 칸이라 행과 열의 뜻을 함께 알린다', () => {
    renderCurrent()

    const rowHeaders = within(screen.getByRole('table')).getAllByRole('rowheader')
    expect(rowHeaders.map((header) => header.textContent)).toEqual(['너울', '물때'])
    expect(rowHeaders.every((header) => header.getAttribute('scope') === 'row')).toBe(true)
    expect(within(screen.getByRole('table')).getAllByRole('cell')).toHaveLength(4)
  })

  it('새 기준 가상의 계산 예 상자는 점선이 아니다(점선은 준비 중만 쓴다)', () => {
    renderCurrent()

    const box = screen.getByRole('complementary', { name: '가상의 계산 예' })
    expect(box.className).toMatch(/hypotheticalCurrent/)
  })

  it('가상의 계산 예와 도식에 가상 표시가, 도식 아래에 설명 문장이 있다', () => {
    renderCurrent()

    expect(screen.getByRole('complementary', { name: '가상의 계산 예' })).not.toBeNull()
    expect(
      screen.getByText('가 항구는 크게 오르내리고 나 항구는 거의 그대로지만 둘 다 1.5에서 끝난다.')
        .tagName,
    ).toBe('FIGCAPTION')
  })

  it('제도 안내에는 기준 시점과 출처 표시가 있고 가상의 예 표시가 없다', () => {
    renderCurrent()

    const note = screen.getByRole('complementary', { name: '제도 안내' })
    expect(within(note).getByText('기준 시점 2026-07-01')).not.toBeNull()
    expect(within(note).getByRole('link', { name: /합성 제도 자료/ }).textContent).toContain(
      '(*출처)',
    )
    expect(within(note).queryByText('가상의 예')).toBeNull()
  })

  it('번호 목록은 ol이고 항목의 출처 표시가 링크다', () => {
    renderCurrent()

    const list = screen.getAllByRole('list').find((element) => element.tagName === 'OL')
    expect(list).toBeDefined()
    expect(within(list!).getByRole('link', { name: /합성 목록 자료/ })).not.toBeNull()
  })

  it('다른 이름은 처음 한 곳에만 괄호로 붙고 도움말 표시는 다른 글의 용어 처음 한 곳에만 있다', () => {
    const { container } = renderCurrent()

    const text = container.textContent ?? ''
    expect(text.split('너울 높이 (파고, swell)')).toHaveLength(2)
    expect(text.split('썰물 간격 (물 빠짐 폭, ebb)')).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: '썰물 간격 도움말' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: '계류 매듭 도움말' })).toHaveLength(1)
    // 이 글이 소유한 용어에는 도움말 표시가 없고, 용어 글자 자체는 누를 수 없다.
    expect(screen.queryByRole('button', { name: '너울 높이 도움말' })).toBeNull()
    expect(screen.queryByRole('link', { name: '썰물 간격' })).toBeNull()
  })

  it('모르는 블록 종류는 그리지 않고 나머지는 그린다', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    renderCurrent([
      { type: 'timeline', text: '그리면 안 되는 문장' },
      { type: 'heading', text: '보여야 하는 소제목' },
    ] as unknown as CurrentArticleBlock[])

    expect(screen.queryByText('그리면 안 되는 문장')).toBeNull()
    expect(screen.getByRole('heading', { name: '보여야 하는 소제목' })).not.toBeNull()
  })

  it('본문 끝 표지는 마지막 블록 바로 뒤에서 본문 끝이라고 보인다', () => {
    const { container } = renderCurrent()

    const end = container.querySelector('[data-article-end]')
    expect(end?.previousElementSibling?.getAttribute('aria-label')).toBe('제도 안내')
    expect(end?.textContent).toBe('본문 끝')
    expect(end?.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })
})

describe('글 화면 부품 소스', () => {
  it('어느 부품도 dangerouslySetInnerHTML을 쓰지 않는다', () => {
    const sources = import.meta.glob<string>('./*.tsx', {
      query: '?raw',
      import: 'default',
      eager: true,
    })
    const components = Object.entries(sources).filter(([path]) => !path.endsWith('.test.tsx'))

    expect(components.length).toBeGreaterThan(5)
    for (const [path, source] of components) {
      expect(source.includes('dangerouslySetInnerHTML'), path).toBe(false)
    }
  })
})
