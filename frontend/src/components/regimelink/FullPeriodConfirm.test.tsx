import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FullPeriodConfirm } from './FullPeriodConfirm'

describe('전체 기간 재계산 확인', () => {
  it('확인 전에는 생성 호출이 없고 확인하면 한 번 부른다', async () => {
    const onConfirm = vi.fn()
    const onReject = vi.fn()
    render(
      <FullPeriodConfirm
        outcome="OUT_OF_PERIOD_CONFIRM_REQUIRED"
        requiredRange={{ start: '2006-10-02', end: '2025-12-30' }}
        affected={[{ publicToken: 'a'.repeat(32), period: { start: '2022-01-03', end: '2025-12-30' } }]}
        onConfirm={onConfirm}
        onReject={onReject}
      />,
    )
    expect(screen.getByText('이 사례를 보려면 전체 기간으로 다시 계산합니다.')).toBeTruthy()
    expect(screen.getByText(/2006-10-02 ~ 2025-12-30/)).toBeTruthy()
    expect(onConfirm).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '다시 계산하고 보기' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('거절하면 확인만 닫히고 계산을 만들지 않는다', async () => {
    const onConfirm = vi.fn()
    const onReject = vi.fn()
    render(
      <FullPeriodConfirm
        outcome="OUT_OF_PERIOD_CONFIRM_REQUIRED"
        onConfirm={onConfirm}
        onReject={onReject}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '그대로 두기' }))
    expect(onReject).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('체결 기록이 없는 실행은 다른 문구를 보인다(T092)', () => {
    render(
      <FullPeriodConfirm
        outcome="FILLS_MISSING_CONFIRM_REQUIRED"
        onConfirm={vi.fn()}
        onReject={vi.fn()}
      />,
    )
    expect(screen.getByText('이 결과는 사후 구간을 보려면 다시 계산합니다.')).toBeTruthy()
    expect(screen.queryByText('이 사례를 보려면 전체 기간으로 다시 계산합니다.')).toBeNull()
  })
})
