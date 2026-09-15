import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NotReadyNotice } from './NotReadyNotice'

/** 표시 영역을 역할로 찾는다. 이 컴포넌트는 언제나 상태 영역 하나만 그린다. */
function readNotice() {
  return screen.getByRole('status')
}

describe('NotReadyNotice', () => {
  it('행동을 주지 않으면 버튼을 그리지 않는다', () => {
    render(<NotReadyNotice message="이 메뉴는 아직 준비 중입니다." />)

    const notice = readNotice()
    expect(within(notice).queryAllByRole('button')).toHaveLength(0)
  })

  it('행동을 주면 버튼이 하나 생기고 누르면 그 행동이 실행된다', async () => {
    const onAction = vi.fn()
    render(
      <NotReadyNotice
        message="섹터 목록을 아직 불러올 수 없습니다."
        action={{ label: '다시 불러오기', onAction }}
      />,
    )

    const notice = readNotice()
    const buttons = within(notice).getAllByRole('button')
    expect(buttons).toHaveLength(1)

    await userEvent.click(buttons[0])
    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('행동이 있든 없든 문장은 언제나 하나 보인다', () => {
    const { rerender } = render(<NotReadyNotice message="이 메뉴는 아직 준비 중입니다." />)

    expect(readNotice().querySelectorAll('p')).toHaveLength(1)
    expect(screen.getByText('이 메뉴는 아직 준비 중입니다.')).toBeDefined()

    rerender(
      <NotReadyNotice
        message="섹터 목록을 아직 불러올 수 없습니다."
        action={{ label: '다시 불러오기', onAction: () => {} }}
      />,
    )

    expect(readNotice().querySelectorAll('p')).toHaveLength(1)
    expect(screen.getByText('섹터 목록을 아직 불러올 수 없습니다.')).toBeDefined()
  })
})
