import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext } from '@/auth/auth-context'
import { defaultValuesOf } from '@/hooks/useStrategySettings'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { resultDetailCompleted as base } from '@/test/resultFixtures'
import {
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from '@/test/strategyRegistryFixtures'
import { CompareTargetPicker } from './CompareTargetPicker'

const api = vi.hoisted(() => ({
  registry: vi.fn(),
  versions: vi.fn(),
  window: vi.fn(),
  used: vi.fn(),
}))
vi.mock('@/api/strategyRegistry', () => ({ fetchStrategyRegistry: api.registry }))
vi.mock('@/api/analysisVersions', () => ({ fetchAnalysisVersions: api.versions }))
vi.mock('@/api/sectors', () => ({ fetchEvaluationWindow: api.window }))
vi.mock('@/api/backtest', async (load) => ({
  ...(await load<typeof import('@/api/backtest')>()),
  fetchUsedStrategies: api.used,
}))

const authenticatedAuthContext = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
} as typeof anonymousAuthContext
const DEFAULT_ROW = /기본 전략 · 표류 균형 맞추기/
const DEFAULT_SETTINGS = '표류 관측 창 45거래일 · 되돌리는 시점 달 끝마다 · 허용 이탈 폭 35퍼센트'

function setup(
  props: Partial<Parameters<typeof CompareTargetPicker>[0]> = {},
  auth = anonymousAuthContext,
) {
  const select = vi.fn()
  render(
    <AuthContext.Provider value={auth}>
      <CompareTargetPicker base={base} room={3} onSelect={select} onClose={() => {}} {...props} />
    </AuthContext.Provider>,
  )
  return select
}

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
  api.used.mockResolvedValue([])
})

describe('비교 목록 (T058, S15P21E205-419)', () => {
  it('기본 전략 묶음에 설정값을 적고, 기간 감소와 선택 불가 이유를 보이며 키보드로 고른 뒤 한 번에 넘긴다', async () => {
    const user = userEvent.setup()
    const select = setup()
    expect(await screen.findByText('이 전략을 고르면 비교 기간이 2거래일 짧아집니다.')).toBeTruthy()
    expect(screen.getByText(unselectableStrategy.unselectableReason!)).toBeTruthy()
    expect(
      (
        screen.getByRole('checkbox', {
          name: new RegExp('기본 전략 · ' + unselectableStrategy.name),
        }) as HTMLInputElement
      ).disabled,
    ).toBe(true)
    const row = screen.getByRole('checkbox', { name: DEFAULT_ROW }) as HTMLInputElement
    expect(row.getAttribute('aria-label')).toContain(DEFAULT_SETTINGS)
    expect(screen.getByRole('button', { name: '비교하기' })).toHaveProperty('disabled', true)
    row.focus()
    await user.keyboard(' ')
    await user.click(screen.getByRole('button', { name: '비교하기 (1)' }))
    expect(select).toHaveBeenCalledTimes(1)
    expect(select.mock.calls[0][0]).toHaveLength(1)
    expect(select.mock.calls[0][0][0]).toMatchObject({
      rulesVersion: 'RULES-2',
      period: { start: '2023-07-05', end: base.config.effectiveEnd },
      parameters: defaultValuesOf(selectableStrategy),
    })
    expect(api.registry).toHaveBeenCalledWith(base.config.items.length, expect.any(AbortSignal))
    // 비로그인이면 써 본 조합을 묻지 않는다.
    expect(api.used).not.toHaveBeenCalled()
  })
  it('로그인했으면 써 본 조합을 내 전략 묶음에 설정값과 함께 올리고, 기본값과 같거나 등록표에 맞지 않는 것은 뺀다', async () => {
    api.used.mockResolvedValue([
      {
        strategyId: selectableStrategy.strategyId,
        rulesVersion: 'RULES-1',
        parameters: {
          driftWindow: 90,
          resetTiming: { kind: 'FIXED_DAYS', span: 30 },
          bandWidth: 40,
        },
      },
      // 기본값과 같은 조합 — 기본값 줄 하나로 접힌다.
      {
        strategyId: selectableStrategy.strategyId,
        rulesVersion: 'RULES-1',
        parameters: defaultValuesOf(selectableStrategy),
      },
      // 허용 범위 밖(간격 5) — 값을 고쳐 넣어 올리지 않는다.
      {
        strategyId: selectableStrategy.strategyId,
        rulesVersion: 'RULES-1',
        parameters: { ...defaultValuesOf(selectableStrategy), bandWidth: 37 },
      },
    ])
    const select = setup({ room: 1 }, authenticatedAuthContext)
    const used = (await screen.findByRole('checkbox', {
      name: /내 전략 · 표류 균형 맞추기/,
    })) as HTMLInputElement
    expect(used.getAttribute('aria-label')).toContain(
      '표류 관측 창 90거래일 · 되돌리는 시점 30거래일마다 · 허용 이탈 폭 40퍼센트',
    )
    expect(screen.getAllByRole('checkbox', { name: /표류 균형 맞추기/ })).toHaveLength(2)
    expect(screen.getByText(/최대 1개까지/)).toBeTruthy()
    fireEvent.click(used)
    // 자리가 하나라 다른 줄은 더 고를 수 없다.
    expect(
      (screen.getByRole('checkbox', { name: DEFAULT_ROW }) as HTMLInputElement).disabled,
    ).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '비교하기 (1)' }))
    expect(select.mock.calls[0][0][0].parameters).toEqual({
      driftWindow: 90,
      resetTiming: { kind: 'FIXED_DAYS', span: 30 },
      bandWidth: 40,
    })
  })
  it('기준 결과와 같은 전략·설정 조합은 후보에서 빼고 설정값이 다른 조합만 남긴다', async () => {
    api.registry.mockResolvedValue({ ...registryFixture, strategies: [selectableStrategy] })
    api.used.mockResolvedValue([
      {
        strategyId: selectableStrategy.strategyId,
        rulesVersion: 'RULES-1',
        parameters: { ...defaultValuesOf(selectableStrategy), bandWidth: 40 },
      },
    ])
    render(
      <AuthContext.Provider value={authenticatedAuthContext}>
        <CompareTargetPicker
          base={{
            ...base,
            config: {
              ...base.config,
              strategyId: selectableStrategy.strategyId,
              parameters: defaultValuesOf(selectableStrategy),
            },
          }}
          room={3}
          onSelect={() => {}}
          onClose={() => {}}
        />
      </AuthContext.Provider>,
    )
    expect(await screen.findByRole('checkbox', { name: /내 전략/ })).toBeTruthy()
    expect(screen.queryByRole('checkbox', { name: DEFAULT_ROW })).toBeNull()
  })
  it('써 본 조합 조회가 실패하면 기본 전략만 보이고 그 사실을 한 줄로 알린다', async () => {
    api.used.mockRejectedValue(new Error('401'))
    setup({}, authenticatedAuthContext)
    expect(await screen.findByText(/내 전략 목록은 지금 불러오지 못했습니다/)).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: DEFAULT_ROW })).toBeTruthy()
  })
  it('과거 준비 자료가 충분하면 기간이 줄어든다고 잘못 안내하지 않는다', async () => {
    api.window.mockResolvedValue({
      tradingDaysFromBase: ['2020-01-02', '2020-01-03', '2020-01-06'],
      commonEnd: base.config.effectiveEnd,
    })
    setup()
    expect(await screen.findByText('준비 기간을 맞춰 결과를 다시 계산합니다.')).toBeTruthy()
    expect(screen.queryByText(/짧아집니다/)).toBeNull()
  })
  it('자리가 없으면 고를 수 없다고 알리고 모든 줄을 막는다', async () => {
    setup({ room: 0 })
    expect(await screen.findByText(/더 고를 자리가 없습니다/)).toBeTruthy()
    for (const box of screen.getAllByRole('checkbox'))
      expect((box as HTMLInputElement).disabled).toBe(true)
  })
  it('등록표 실패는 대체 목록 대신 사유와 수동 재조회로 보인다', async () => {
    api.registry.mockRejectedValue(new Error('등록표를 받을 수 없습니다.'))
    setup()
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByRole('checkbox')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 불러오기' }))
    expect(await screen.findByText('등록표를 받을 수 없습니다.')).toBeTruthy()
  })
})
