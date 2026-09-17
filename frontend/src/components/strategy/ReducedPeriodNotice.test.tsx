import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReducedPeriodNotice } from './ReducedPeriodNotice'
import type { ReducedPeriodInput } from './reducedPeriod'

const input: ReducedPeriodInput = {
  window: { start: '2019-06-03', end: '2024-12-30' },
  reduction: { startWithoutWarmup: '2019-01-02', bindingCandidate: '091160.XKRX' },
  bindingName: '가섹터',
  warmupTradingDays: 81,
  causes: [
    { label: '흐름 창', value: 60, unit: '거래일' },
    { label: '쉬는 날', value: 21, unit: '거래일' },
  ],
}

describe('ReducedPeriodNotice', () => {
  it('줄어든 구간과 시작일을 정한 섹터, 워밍업을 이루는 항목을 한 문장에 담는다', () => {
    render(<ReducedPeriodNotice input={input} />)

    const notice = screen.getByRole('status', { name: '줄어든 평가 기간' })
    const text = notice.textContent ?? ''
    expect(text).toContain('2019-06-03 ~ 2024-12-30')
    expect(text).toContain('2019-01-02')
    expect(text).toContain('가섹터')
    expect(text).toContain('흐름 창 60거래일·쉬는 날 21거래일')
    expect(text).toContain('81거래일')
  })

  it('워밍업 항목 이름이 없으면 워밍업이라고만 적고 이름을 지어내지 않는다', () => {
    render(<ReducedPeriodNotice input={{ ...input, causes: [] }} />)

    const text = screen.getByRole('status').textContent ?? ''
    expect(text).toContain('워밍업 81거래일')
    expect(text).not.toContain('지금 설정의')
  })

  it('원인을 받지 못한 줄어든 판정은 판정 문장을 그대로 보인다', () => {
    render(<ReducedPeriodNotice input={null} fallbackMessage="평가 기간이 줄어듭니다." />)

    expect(screen.getByRole('status', { name: '줄어든 평가 기간' }).textContent).toBe(
      '평가 기간이 줄어듭니다.',
    )
  })

  it('줄지 않았으면 아무것도 그리지 않는다', () => {
    const { container } = render(<ReducedPeriodNotice input={null} />)

    expect(container.childElementCount).toBe(0)
    expect(screen.queryByRole('status')).toBeNull()
  })
})
