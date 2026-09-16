import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { MemoryRouter } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GlossaryLookup } from '@/api/learningGlossary'
import { TermHelp } from './TermHelp'

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

describe('TermHelp', () => {
  it('열기 전에는 설명을 요청하지 않는다', () => {
    renderHelp()
    expect(fetchGlossaryEntry).not.toHaveBeenCalled()
  })

  it('열면 받은 문장을 그대로 보이고 글로 가는 연결은 돌아올 자리만 싣는다', async () => {
    fetchGlossaryEntry.mockResolvedValue(ready)
    const { user, trigger } = renderHelp()

    await user.click(trigger)

    expect(fetchGlossaryEntry).toHaveBeenCalledWith('synthetic-term', expect.any(AbortSignal))
    expect(await screen.findByText('합성 용어를 설명하는 한 문장이다.')).not.toBeNull()

    await user.click(screen.getByRole('link', { name: /합성 글/ }))

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
