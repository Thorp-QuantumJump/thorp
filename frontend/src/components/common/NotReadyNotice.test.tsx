import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NotReadyNotice } from './NotReadyNotice'

describe('NotReadyNotice', () => {
  it('행동을 주지 않으면 버튼을 그리지 않는다', () => {
    const { container } = render(<NotReadyNotice message="이 메뉴는 아직 준비 중입니다." />)

    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(container.querySelectorAll('p')).toHaveLength(1)
    expect(screen.getByText('이 메뉴는 아직 준비 중입니다.')).toBeDefined()
  })

  it('행동을 주면 버튼이 하나 생기고 누르면 그 행동이 실행된다', async () => {
    const onAction = vi.fn()
    render(
      <NotReadyNotice
        message="섹터 목록을 아직 불러올 수 없습니다."
        action={{ label: '다시 불러오기', onAction }}
      />,
    )

    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(1)

    await userEvent.click(buttons[0])
    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('다시 시도할 수 있는 자리에서만 문장을 읽어 주고, 버튼 이름은 함께 읽지 않는다', () => {
    const { rerender } = render(<NotReadyNotice message="이 메뉴는 아직 준비 중입니다." />)

    // 화면이 열릴 때 함께 만들어지고 그 자리에서 바뀌지 않는 문장이다.
    expect(screen.queryByRole('status')).toBeNull()

    rerender(
      <NotReadyNotice
        message="섹터 목록을 아직 불러올 수 없습니다."
        action={{ label: '다시 불러오기', onAction: () => {} }}
      />,
    )

    const liveRegion = screen.getByRole('status')
    expect(liveRegion.textContent).toBe('섹터 목록을 아직 불러올 수 없습니다.')
    expect(within(liveRegion).queryAllByRole('button')).toHaveLength(0)
  })
})
