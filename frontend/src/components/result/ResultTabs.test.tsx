import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ResultTabs } from './ResultTabs'

/** 005 T098. FR-007·FR-022 — 탭 줄의 키보드 조작·지연 마운트·비활성 사유. */

function Counter({ name, onMount }: { name: string; onMount: (name: string) => void }) {
  const [mounted] = useState(() => {
    onMount(name)
    return true
  })
  return <p>{mounted ? `${name} 내용` : null}</p>
}

function renderTabs(onMount = vi.fn()) {
  render(
    <ResultTabs
      label="시험 탭"
      defaultTab="a"
      tabs={[
        { id: 'a', label: '첫째', content: <Counter name="첫째" onMount={onMount} /> },
        { id: 'b', label: '둘째', content: <Counter name="둘째" onMount={onMount} /> },
        {
          id: 'c',
          label: '셋째',
          disabled: true,
          reason: '셋째는 볼 자료가 없습니다.',
          content: <Counter name="셋째" onMount={onMount} />,
        },
        { id: 'd', label: '넷째', content: <Counter name="넷째" onMount={onMount} /> },
      ]}
    />,
  )
  return { onMount }
}

describe('ResultTabs', () => {
  it('기본 탭만 열려 있고 다른 탭의 내용은 고를 때 처음 붙는다', async () => {
    const user = userEvent.setup()
    const { onMount } = renderTabs()

    expect(screen.getByRole('tab', { name: '첫째' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel').textContent).toContain('첫째 내용')
    expect(onMount.mock.calls.map((call) => call[0])).toEqual(['첫째'])

    await user.click(screen.getByRole('tab', { name: '둘째' }))
    expect(screen.getByRole('tabpanel').textContent).toContain('둘째 내용')
    expect(onMount.mock.calls.map((call) => call[0])).toEqual(['첫째', '둘째'])
  })

  it('떠난 탭의 내용은 내리지 않고 숨긴다 — 돌아와도 다시 붙지 않는다', async () => {
    const user = userEvent.setup()
    const { onMount } = renderTabs()

    await user.click(screen.getByRole('tab', { name: '둘째' }))
    await user.click(screen.getByRole('tab', { name: '첫째' }))

    expect(screen.getAllByRole('tabpanel', { hidden: true })).toHaveLength(2)
    expect(screen.getByRole('tabpanel').textContent).toContain('첫째 내용')
    expect(onMount).toHaveBeenCalledTimes(2)
  })

  it('비활성 탭은 눌러도 열리지 않고 사유가 탭 줄 아래에 있으며 aria-describedby로 이어진다', async () => {
    const user = userEvent.setup()
    renderTabs()

    const disabled = screen.getByRole('tab', { name: '셋째' })
    expect(disabled.getAttribute('aria-disabled')).toBe('true')
    const reason = document.getElementById(disabled.getAttribute('aria-describedby') ?? '')
    expect(reason?.textContent).toBe('셋째는 볼 자료가 없습니다.')

    await user.click(disabled)
    expect(screen.getByRole('tab', { name: '첫째' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.queryByText('셋째 내용')).toBeNull()
  })

  it('방향키·Home·End로 탭을 옮기면 초점이 탭에 남고 옮긴 탭이 열린다. 비활성 탭은 초점만 받는다', async () => {
    const user = userEvent.setup()
    renderTabs()
    const [first, second, third, fourth] = ['첫째', '둘째', '셋째', '넷째'].map((name) =>
      screen.getByRole('tab', { name }),
    )

    first.focus()
    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(second)
    expect(second.getAttribute('aria-selected')).toBe('true')

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(third)
    // 비활성 탭은 열리지 않아 앞 탭이 그대로 열려 있다.
    expect(second.getAttribute('aria-selected')).toBe('true')

    await user.keyboard('{End}')
    expect(document.activeElement).toBe(fourth)
    expect(fourth.getAttribute('aria-selected')).toBe('true')

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(first)

    await user.keyboard('{Home}')
    expect(document.activeElement).toBe(first)
    expect(first.getAttribute('aria-selected')).toBe('true')
    // 열린 탭만 Tab 순서에 있다.
    expect(first.getAttribute('tabindex')).toBe('0')
    expect(second.getAttribute('tabindex')).toBe('-1')
  })
})
