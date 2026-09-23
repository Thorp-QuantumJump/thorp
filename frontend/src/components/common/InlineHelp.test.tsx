import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, describe, expect, it } from 'vitest'
import { InlineHelp } from './InlineHelp'

/**
 * 화면이 가진 설명을 물음표 뒤로 내리는 도움말(005 FR-022 접근성 최소 요건).
 *
 * <p>문장은 합성 값이다. 실제 화면 문구를 옮겨 적으면 문구가 바뀔 때마다 이 파일이 함께 깨진다.
 */

beforeAll(() => {
  // 말풍선 위치 계산이 크기 관찰을 쓴다. jsdom에는 없다.
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

  it('마우스를 얹으면 화면이 넘긴 설명을 그대로 보인다', async () => {
    const { user, trigger } = renderHelp()

    await user.hover(trigger)

    expect(await screen.findByText('합성 지표를 설명하는 한 문장이다.')).not.toBeNull()
  })

  it('초점만 받아도 열리고 Esc로 닫으면 초점이 트리거에 남는다', async () => {
    const { user, trigger } = renderHelp()

    await user.tab()
    expect(document.activeElement).toBe(trigger)
    expect(await screen.findByText('합성 지표를 설명하는 한 문장이다.')).not.toBeNull()

    await user.keyboard('{Escape}')
    await act(async () => {})

    expect(screen.queryByText('합성 지표를 설명하는 한 문장이다.')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })

  /**
   * 닫은 뒤에도 계속 닫혀 있어야 한다.
   *
   * <p>예약된 열기가 남아 있으면 Esc 로 닫은 도움말이 잠시 뒤 저절로 다시 열린다. 앞 검사는 Esc 직후
   * 한 번만 보므로 그 사고를 지나친다 — 실제로 지나쳤고, 전체 실행에서는 기계가 느려져 타이머가 먼저
   * 깨어나는 바람에 같은 코드가 어떤 때는 통과하고 어떤 때는 실패했다(S15P21E205-422).
   *
   * <p>그래서 여는 틈보다 넉넉히 기다린 뒤에 다시 본다. 실제 시간을 쓴다 — 가짜 시계로 바꾸면 라이브러리가
   * 쓰는 다른 시간까지 함께 멈춰 이 검사가 무엇을 지키는지 흐려진다.
   */
  it('Esc로 닫은 뒤에는 예약된 열기가 남아 저절로 다시 열리지 않는다', async () => {
    const { user } = renderHelp()

    await user.tab()
    expect(await screen.findByText('합성 지표를 설명하는 한 문장이다.')).not.toBeNull()
    await user.keyboard('{Escape}')

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 300))
    })

    expect(screen.queryByText('합성 지표를 설명하는 한 문장이다.')).toBeNull()
  })
})
