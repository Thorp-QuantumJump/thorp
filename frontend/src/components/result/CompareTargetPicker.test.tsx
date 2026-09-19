import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resultDetailCompleted as base } from '@/test/resultFixtures'
import {
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from '@/test/strategyRegistryFixtures'
import { CompareTargetPicker } from './CompareTargetPicker'

const api = vi.hoisted(() => ({ registry: vi.fn(), versions: vi.fn(), window: vi.fn() }))
vi.mock('@/api/strategyRegistry', () => ({ fetchStrategyRegistry: api.registry }))
vi.mock('@/api/analysisVersions', () => ({ fetchAnalysisVersions: api.versions }))
vi.mock('@/api/sectors', () => ({ fetchEvaluationWindow: api.window }))
beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  api.registry.mockResolvedValue({
    ...registryFixture,
    strategies: [{ ...selectableStrategy, warmupTradingDays: 2 }, unselectableStrategy],
  })
  api.versions.mockResolvedValue({
    strategyRules: [
      { strategyId: selectableStrategy.strategyId, rulesVersion: 'RULES-2', status: 'REGISTERED' },
    ],
  })
  api.window.mockResolvedValue({
    tradingDaysFromBase: ['2023-07-03', '2023-07-04', '2023-07-05'],
    commonEnd: base.config.effectiveEnd,
  })
})
describe('비교 목록 (T058)', () => {
  it('달력으로 확인한 기간 감소와 선택 불가 이유를 보이며 키보드로 선택한다', async () => {
    const user = userEvent.setup()
    const select = vi.fn()
    render(<CompareTargetPicker base={base} onSelect={select} onClose={() => {}} />)
    expect(await screen.findByText('이 전략을 고르면 비교 기간이 2거래일 짧아집니다.')).toBeTruthy()
    expect(screen.getByText(unselectableStrategy.unselectableReason!)).toBeTruthy()
    expect(
      (screen.getByRole('button', { name: unselectableStrategy.name }) as HTMLButtonElement)
        .disabled,
    ).toBe(true)
    screen.getByRole('button', { name: selectableStrategy.name }).focus()
    await user.keyboard('{Enter}')
    expect(select).toHaveBeenCalledWith(
      expect.objectContaining({
        rulesVersion: 'RULES-2',
        period: { start: '2023-07-05', end: base.config.effectiveEnd },
      }),
    )
    expect(api.registry).toHaveBeenCalledWith(base.config.items.length, expect.any(AbortSignal))
  })
  it('과거 준비 자료가 충분하면 기간이 줄어든다고 잘못 안내하지 않는다', async () => {
    api.window.mockResolvedValue({
      tradingDaysFromBase: ['2020-01-02', '2020-01-03', '2020-01-06'],
      commonEnd: base.config.effectiveEnd,
    })
    render(<CompareTargetPicker base={base} onSelect={() => {}} onClose={() => {}} />)
    expect(await screen.findByText('준비 기간을 맞춰 두 결과를 다시 계산합니다.')).toBeTruthy()
    expect(screen.queryByText(/짧아집니다/)).toBeNull()
  })
  it('등록표 실패는 대체 목록 대신 사유와 수동 재조회로 보인다', async () => {
    api.registry.mockRejectedValue(new Error('등록표를 받을 수 없습니다.'))
    render(<CompareTargetPicker base={base} onSelect={() => {}} onClose={() => {}} />)
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByRole('button', { name: selectableStrategy.name })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 불러오기' }))
    expect(await screen.findByText('등록표를 받을 수 없습니다.')).toBeTruthy()
  })
})
