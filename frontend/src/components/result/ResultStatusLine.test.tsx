import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { resultDetailCompleted } from '@/test/resultFixtures'
import type { PublicResultDetail } from '@/types/result'
import { ResultStatusLine } from './ResultStatusLine'

/** 005 T076. FR-012~FR-013, SC-005, quickstart 5. */

function detail(overrides: Partial<PublicResultDetail>): PublicResultDetail {
  return { ...resultDetailCompleted, ...overrides }
}

describe('ResultStatusLine', () => {
  it('취소 결과는 완료 범위와 계산되지 않은 지표를 한 문장으로 보이고 숫자 0을 만들지 않는다', () => {
    const cancelled = detail({
      status: 'CANCELLED',
      partial: true,
      summary: null,
      summaryUnavailable: 'CANCELLED_WHILE_RUN_CONTINUED',
      exceptionKinds: null,
    })
    const { container } = render(
      <ResultStatusLine detail={cancelled} completedThrough="2025-03-19" />,
    )

    const line = screen.getByRole('status')
    expect(line.textContent).toContain('2025-03-19까지 계산된 부분 결과입니다.')
    expect(line.textContent).toContain('취소 시점까지의 지표는 계산되지 않았습니다.')
    expect(container.textContent).not.toMatch(/0%|0원/)
    expect(container.querySelector('ul')).toBeNull()
  })

  it('계산 시작 전 취소는 완료 거래일이 없다고만 밝히고 부분 결과라고 하지 않는다', () => {
    const cancelled = detail({
      status: 'CANCELLED',
      partial: true,
      summary: null,
      summaryUnavailable: 'CANCELLED_BEFORE_START',
      exceptionKinds: null,
    })
    const { container } = render(
      <ResultStatusLine detail={cancelled} completedThrough={null} />,
    )

    const line = screen.getByRole('status')
    expect(line.textContent).not.toContain('부분 결과')
    expect(line.textContent).toContain('계산을 시작하기 전에 취소되어 완료된 거래일이 없습니다.')
    expect(container.textContent).not.toMatch(/0%|0원/)
  })

  it('예외가 있으면 목록을 만들지 않고 한 문장 안에 이어 쓴다', () => {
    const { container } = render(
      <ResultStatusLine detail={resultDetailCompleted} completedThrough={null} />,
    )

    expect(screen.getByRole('status').textContent).toContain('자료 부족·계산 제한 기록이 1건 있습니다.')
    expect(container.querySelector('ul')).toBeNull()
  })

  it('예외가 없으면 자료 부족 문장이 없다', () => {
    render(
      <ResultStatusLine
        detail={detail({
          exceptionKinds: [],
          summary: { ...resultDetailCompleted.summary!, exceptionCount: 0 },
        })}
        completedThrough={null}
      />,
    )

    expect(screen.getByRole('status').textContent).not.toContain('자료 부족')
  })

  it('실패 사유와 사용자가 누르는 다시 시도를 보인다', () => {
    const onRetry = vi.fn()
    render(
      <ResultStatusLine
        detail={detail({
          status: 'FAILED',
          summary: null,
          exceptionKinds: null,
          failure: { code: 'ENGINE_ERROR', message: '계산 엔진이 응답하지 않았습니다.' },
        })}
        completedThrough={null}
        onRetry={onRetry}
      />,
    )

    expect(screen.getByRole('status').textContent).toContain('계산 엔진이 응답하지 않았습니다.')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
