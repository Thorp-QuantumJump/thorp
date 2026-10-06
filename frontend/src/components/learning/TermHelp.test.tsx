import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { Route, Routes, useLocation } from 'react-router-dom'
import { MemoryRouter } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GlossaryLookup } from '@/api/learningGlossary'
import { TermHelp } from './TermHelp'
import type { ComponentProps } from 'react'

/**
 * 용어 도움말의 세 상태와 키보드 조작(013 term-help-ui §2, 003 FR-016·NFR-001).
 *
 * <p>용어·문장은 합성 값이다. 013 초안 자리의 실제 문장을 옮겨 적지 않는다.
 */

const fetchGlossaryEntry = vi.hoisted(() =>
  vi.fn<(termId: string, signal?: AbortSignal) => Promise<GlossaryLookup>>(),
)

vi.mock('@/api/learningGlossary', () => ({ fetchGlossaryEntry }))

beforeAll(() => {
  // Popover의 위치 계산이 크기 관찰을 쓴다. jsdom에는 없다.
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

beforeEach(() => {
  fetchGlossaryEntry.mockReset()
})

const ready: GlossaryLookup = {
  status: 'ready',
  entry: {
    termId: 'synthetic-term',
    label: '합성 용어',
    sentence: '합성 용어를 설명하는 한 문장이다.',
    articleId: 'synthetic-article',
    articleTitle: '합성 글',
  },
}

function LocationProbe() {
  const location = useLocation()
  return (
    <output data-testid="location">
      {location.pathname}|{JSON.stringify(location.state)}
    </output>
  )
}

function renderHelp() {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/strategy']}>
      <Routes>
        <Route
          path="/strategy"
          element={
            <TermHelp
              termId="synthetic-term"
              label="합성 용어"
              returnTo={{ path: '/strategy', label: '전략 선택' }}
            />
          }
        />
        <Route path="/learn/:articleId" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
  return { user, trigger: screen.getByRole('button', { name: '합성 용어 도움말' }) }
}

/** 도움말을 붙인 진단 화면 대역. 입력 하나와 도움말 하나를 가진다. */
function HostForm() {
  const [value, setValue] = useState('')
  return (
    <form>
      <label>
        합성 입력
        <input value={value} onChange={(event) => setValue(event.target.value)} />
      </label>
      <TermHelp
        termId="synthetic-term"
        label="합성 용어"
        returnTo={{ path: '/strategy', label: '전략 선택' }}
      />
    </form>
  )
}

describe('TermHelp', () => {
  it('열기 전에는 설명을 요청하지 않고 트리거는 button이다', () => {
    const { trigger } = renderHelp()
    expect(fetchGlossaryEntry).not.toHaveBeenCalled()
    expect(trigger.tagName).toBe('BUTTON')
    expect(trigger.getAttribute('type')).toBe('button')
  })

  it('열고 닫는 동안 호출한 화면의 입력이 그대로다', async () => {
    fetchGlossaryEntry.mockResolvedValue({ status: 'notReady' })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/strategy']}>
        <HostForm />
      </MemoryRouter>,
    )
    const input = screen.getByRole('textbox', { name: '합성 입력' })
    fireEvent.change(input, { target: { value: '12.5' } })

    await user.click(screen.getByRole('button', { name: '합성 용어 도움말' }))
    expect(await screen.findByText('설명 준비 중')).not.toBeNull()
    await user.keyboard('{Escape}')
    await act(async () => {})

    expect((screen.getByRole('textbox', { name: '합성 입력' }) as HTMLInputElement).value).toBe(
      '12.5',
    )
  })

  it('열면 받은 문장을 그대로 보이고 글로 가는 연결은 돌아올 자리만 싣는다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderHelp()

    await user.click(trigger)

    expect(fetchGlossaryEntry).toHaveBeenCalledWith('synthetic-term', expect.any(AbortSignal))
    expect(await screen.findByText('합성 용어를 설명하는 한 문장이다.')).not.toBeNull()

    const articleLink = screen.getByRole('link', { name: /합성 글/ })
    expect(articleLink.getAttribute('href')).toBe('/learn/synthetic-article')
    expect(articleLink.getAttribute('target')).toBeNull()
    await user.click(articleLink)

    const location = screen.getByTestId('location').textContent ?? ''
    expect(location).toContain('/learn/synthetic-article')
    expect(location).toContain('{"learningReturn":{"path":"/strategy","label":"전략 선택"}}')
  })

  it('게시된 설명이 없으면 설명 준비 중만 보이고 문장을 짓지 않는다', async () => {
    fetchGlossaryEntry.mockResolvedValue({ status: 'notReady' })
    const { user, trigger } = renderHelp()

    await user.click(trigger)

    expect(await screen.findByText('설명 준비 중')).not.toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('불러오지 못하면 설명 준비 중과 다른 문장과 다시 시도를 보이고, 누를 때만 다시 부른다', async () => {
    fetchGlossaryEntry.mockResolvedValueOnce({ status: 'failed' }).mockResolvedValueOnce(ready)
    const { user, trigger } = renderHelp()

    await user.click(trigger)

    expect((await screen.findByRole('alert')).textContent).toBe('설명을 불러오지 못했습니다.')
    expect(screen.queryByText('설명 준비 중')).toBeNull()
    expect(fetchGlossaryEntry).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('합성 용어를 설명하는 한 문장이다.')).not.toBeNull()
    expect(fetchGlossaryEntry).toHaveBeenCalledTimes(2)
  })

  it('키보드로 열고 Esc로 닫으면 초점이 트리거로 돌아온다', async () => {
    fetchGlossaryEntry.mockResolvedValue({ status: 'notReady' })
    const { user, trigger } = renderHelp()

    trigger.focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByText('설명 준비 중')).not.toBeNull()

    await user.keyboard('{Escape}')
    await act(async () => {})

    expect(screen.queryByText('설명 준비 중')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})

/** 013 T150. 2판 선택 prop(term-help-ui §2·§5, quickstart 12-2 컴포넌트 부분). */
describe('TermHelp — 2판 선택 prop', () => {
  function renderWith(props: Partial<ComponentProps<typeof TermHelp>>) {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/strategy']}>
        <Routes>
          <Route
            path="/strategy"
            element={<TermHelp termId="synthetic-term" label="합성 용어" {...props} />}
          />
          <Route path="/learn/:articleId" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    )
    return { user, trigger: screen.getByRole('button', { name: '합성 용어 도움말' }) }
  }

  /** 창 안의 글자를 순서대로 모은다. 순서를 문서 위치로 비교한다. */
  function inOrder(...texts: string[]) {
    const nodes = texts.map((text) => screen.getByText(text))
    for (let index = 1; index < nodes.length; index += 1) {
      const relation = nodes[index - 1].compareDocumentPosition(nodes[index])
      expect(relation & Node.DOCUMENT_POSITION_FOLLOWING, `${texts[index - 1]} → ${texts[index]}`).toBeTruthy()
    }
  }

  const fallback = { fallbackMeaning: '등록표의 합성 뜻 문장이다.', note: '합성 상한 처리 안내다.' }

  it('ready면 013 뜻 문장·note·글 연결 순서이고 등록표 뜻 문장은 없다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderWith(fallback)

    await user.click(trigger)
    await screen.findByText('합성 용어를 설명하는 한 문장이다.')

    expect(screen.queryByText('등록표의 합성 뜻 문장이다.')).toBeNull()
    inOrder('합성 용어', '합성 용어를 설명하는 한 문장이다.', '합성 상한 처리 안내다.', '‘합성 글’ 글 읽기')
  })

  it('notReady면 등록표 뜻 문장·note 순서이고 설명 준비 중과 연결이 없다', async () => {
    fetchGlossaryEntry.mockResolvedValue({ status: 'notReady' })
    const { user, trigger } = renderWith(fallback)

    await user.click(trigger)
    await screen.findByText('등록표의 합성 뜻 문장이다.')

    inOrder('등록표의 합성 뜻 문장이다.', '합성 상한 처리 안내다.')
    expect(screen.queryByText('설명 준비 중')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('failed면 다시 시도와 note이고 등록표 뜻 문장은 없다', async () => {
    fetchGlossaryEntry.mockResolvedValue({ status: 'failed' })
    const { user, trigger } = renderWith(fallback)

    await user.click(trigger)
    await screen.findByRole('alert')

    inOrder('설명을 불러오지 못했습니다.', '다시 시도', '합성 상한 처리 안내다.')
    expect(screen.queryByText('등록표의 합성 뜻 문장이다.')).toBeNull()
  })

  it('note만 주면 9월 판 그대로 note가 맨 앞이고 notReady에서 설명 준비 중을 덧붙이지 않는다', async () => {
    fetchGlossaryEntry.mockResolvedValueOnce({ status: 'notReady' })
    const { user, trigger } = renderWith({ note: '합성 축 안내다.' })

    await user.click(trigger)
    await screen.findByText('합성 축 안내다.')
    await act(async () => {})

    expect(screen.queryByText('설명 준비 중')).toBeNull()
  })

  it('note만 주고 ready면 note가 용어 이름보다 앞이다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderWith({ note: '합성 축 안내다.' })

    await user.click(trigger)
    await screen.findByText('합성 용어를 설명하는 한 문장이다.')

    inOrder('합성 축 안내다.', '합성 용어', '합성 용어를 설명하는 한 문장이다.')
  })

  it('articleLinkLabel을 주면 글 연결 문구가 바뀐다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderWith({ articleLinkLabel: '이 설정을 설명하는 글 보기' })

    await user.click(trigger)

    const link = await screen.findByRole('link', { name: '이 설정을 설명하는 글 보기' })
    expect(link.getAttribute('href')).toBe('/learn/synthetic-article')
  })

  it('returnTo가 없으면 글 연결에 state를 싣지 않는다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderWith({})

    await user.click(trigger)
    await user.click(await screen.findByRole('link', { name: /합성 글/ }))

    expect(screen.getByTestId('location').textContent).toBe('/learn/synthetic-article|null')
  })
})
