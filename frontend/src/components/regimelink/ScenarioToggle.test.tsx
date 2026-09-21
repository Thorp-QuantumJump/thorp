import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { RegimeLinkView } from '@/types/regimeLink'
import { ScenarioToggle } from './ScenarioToggle'

const pending: RegimeLinkView = {
  basis: { kind: 'HISTORICAL_REVISION' },
  linkId: 'RL-9',
  publicToken: 'b'.repeat(32),
  caseId: 'SNAP~2012-03',
  scenario: 'FRESH_CASH',
  status: 'PENDING_RUN',
  postWindow: {
    d0: '2012-04-02',
    d21: '2012-05-02',
    d63: '2012-07-03',
    completedTradingDays: 63,
    complete21: true,
    complete63: true,
  },
  availability: { state: 'NORMAL' },
  runProgress: { status: 'QUEUED', queueWaitSeconds: 12 },
  failure: null,
  result: null,
}

describe('현금 시작 시나리오 토글', () => {
  it('켤 때만 생성 요청을 부른다', async () => {
    const onEnable = vi.fn()
    render(<ScenarioToggle onEnable={onEnable} freshLinks={[]} />)
    expect(onEnable).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('checkbox', { name: /그날 현금으로 새로 시작/ }))
    expect(onEnable).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('checkbox', { name: /그날 현금으로 새로 시작/ }))
    expect(onEnable).toHaveBeenCalledTimes(1)
  })

  it('켠 자리에 진행 상태와 고정 문구를 보인다', async () => {
    render(<ScenarioToggle onEnable={vi.fn()} freshLinks={[pending]} />)
    await userEvent.click(screen.getByRole('checkbox', { name: /그날 현금으로 새로 시작/ }))
    expect(
      screen.getByText('보유 상태와 거래 시점이 달라 연속 운용 결과와 다릅니다.'),
    ).toBeTruthy()
    expect(screen.getByText('계산을 기다리는 중입니다')).toBeTruthy()
  })
})
