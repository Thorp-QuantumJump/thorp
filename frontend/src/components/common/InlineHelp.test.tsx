import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, describe, expect, it } from 'vitest'
import { InlineHelp } from './InlineHelp'

/**
 * 화면이 가진 설명을 물음표 뒤로 내리는 도움말(005 FR-022 접근성 최소 요건).
 *
 * <p>문장은 합성 값이다. 실제 화면 문구를 옮겨 적으면 문구가 바뀔 때마다 이 파일이 함께 깨진다.
 */

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

function renderHelp() {
  const user = userEvent.setup()
  render(
    <InlineHelp label="합성 지표">
      <p>합성 지표를 설명하는 한 문장이다.</p>
    </InlineHelp>,
  )
  return { user, trigger: screen.getByRole('button', { name: '합성 지표 도움말' }) }
}

describe('InlineHelp', () => {
  it('펼치기 전에는 설명을 보이지 않는다', () => {
    renderHelp()

    expect(screen.queryByText('합성 지표를 설명하는 한 문장이다.')).toBeNull()
  })

  it('마우스를 올리면 화면이 넘긴 설명을 그대로 보인다', async () => {
    const { user, trigger } = renderHelp()

    await user.hover(trigger)

    expect(await screen.findByText('합성 지표를 설명하는 한 문장이다.')).not.toBeNull()
  })

  it('키보드 포커스로 열고 Esc로 닫는다', async () => {
    const { user, trigger } = renderHelp()

    await user.tab()
    expect(await screen.findByText('합성 지표를 설명하는 한 문장이다.')).not.toBeNull()

    await user.keyboard('{Escape}')

    expect(screen.queryByText('합성 지표를 설명하는 한 문장이다.')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})
