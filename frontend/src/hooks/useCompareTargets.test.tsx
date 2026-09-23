import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { BacktestRequestError } from '@/api/backtest'
import { ResultError } from '@/api/result'
import { CompareTargetChip } from '@/components/result/CompareTargetChip'
import { PeriodMismatchDialog } from '@/components/result/PeriodMismatchDialog'
import { defaultValuesOf } from '@/hooks/useStrategySettings'
import {
  resultDetailCompleted as base,
  resultDetailRunning,
  resultTimeseries,
} from '@/test/resultFixtures'
import { plainStrategy, selectableStrategy } from '@/test/strategyRegistryFixtures'
import type { PublicResultDetail } from '@/types/result'
import { useCompareTargets } from './useCompareTargets'

const api = vi.hoisted(() => ({
  create: vi.fn(),
  detail: vi.fn(),
  series: vi.fn(),
  retry: vi.fn(),
}))
vi.mock('@/api/backtest', async (load) => ({
  ...(await load<typeof import('@/api/backtest')>()),
  createBacktest: api.create,
}))
vi.mock('@/api/result', async (load) => ({
  ...(await load<typeof import('@/api/result')>()),
  fetchResult: api.detail,
  fetchResultTimeseries: api.series,
  retryResult: api.retry,
}))
const targetToken = 'b'.repeat(32)
const newBase = 'c'.repeat(32)
const newTarget = 'd'.repeat(32)
const secondToken = 'e'.repeat(32)
const choice = {
  strategy: { ...selectableStrategy, warmupTradingDays: 0 },
  registryVersion: 'registry-new',
  rulesVersion: 'rules-new',
  parameters: defaultValuesOf(selectableStrategy),
}
const secondChoice = {
  strategy: { ...plainStrategy, warmupTradingDays: 0 },
  registryVersion: 'registry-new',
  rulesVersion: 'rules-plain',
  parameters: defaultValuesOf(plainStrategy),
}
let target: PublicResultDetail
let second: PublicResultDetail

function Harness() {
  const compare = useCompareTargets(base)
  const location = useLocation()
  return (
    <>
      <p>기준 결과 유지</p>
      <button onClick={() => compare.select([choice])}>전략 선택</button>
      <button onClick={() => compare.select([choice, secondChoice])}>둘 선택</button>
      <button
        onClick={() =>
          compare.select([
            {
              ...choice,
              strategy: { ...choice.strategy, warmupTradingDays: 80 },
              period: { start: '2024-01-02', end: base.config.effectiveEnd },
            },
          ])
        }
      >
        긴 전략 선택
      </button>
      <output aria-label="상태">
        {compare.targets.map((item) => item.targetState).join(',') || 'NONE'}
      </output>
      <output aria-label="주소">{location.pathname + location.search}</output>
      <output aria-label="복귀">{compare.returnTo}</output>
      <output aria-label="자리">{compare.room}</output>
      {compare.compatibleTargets.length > 0 ? (
        <p>비교 곡선 사용 가능 {compare.compatibleTargets.length}</p>
      ) : null}
      {compare.targets.map((item) => (
        <CompareTargetChip
          key={item.key}
          state={item}
          name={item.detail?.strategyName ?? item.choice?.strategy.name ?? '대상'}
          onRetry={() => compare.retry(item.key)}
          onRemove={() => compare.remove(item.key)}
        />
      ))}
      {compare.period ? (
        <PeriodMismatchDialog
          period={compare.period}
          busy={compare.recalculating}
          error={compare.dialogError}
          onConfirm={() => void compare.confirmPeriod()}
          onCancel={compare.cancelPeriod}
        />
      ) : null}
    </>
  )
}
function setup(search = '') {
  render(
    <MemoryRouter initialEntries={['/results/' + base.publicToken + search]}>
      <Harness />
    </MemoryRouter>,
  )
}
beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  target = { ...base, publicToken: targetToken, strategyName: '비교용 전략' }
  second = { ...base, publicToken: secondToken, strategyName: '둘째 전략' }
  api.detail.mockImplementation(async (requested: string) =>
    requested === secondToken ? second : target,
  )
  api.series.mockResolvedValue(resultTimeseries)
  api.create.mockResolvedValue({
    publicToken: targetToken,
    resultSource: 'REUSED',
    status: 'COMPLETED',
  })
})
afterEach(() => vi.useRealTimers())

describe('비교 실행 흐름 (quickstart 3)', () => {
  it('대상의 시계열에 그릴 값이 없으면 곡선이 준비됐다고 표시하지 않는다', async () => {
    api.series.mockResolvedValue({ ...resultTimeseries, points: [] })
    setup('?compare=' + targetToken)
    expect(await screen.findByText('비교 곡선을 그릴 자산가치 기록이 없습니다.')).toBeTruthy()
    expect(screen.queryByText(/비교 곡선 사용 가능/)).toBeNull()
    expect(api.create).not.toHaveBeenCalled()
  })
  it('선택 전에는 조회하지 않고, 고르면 같은 조건의 요청 한 건과 공개 주소로 이어진다', async () => {
    setup()
    expect(api.detail).not.toHaveBeenCalled()
    expect(screen.getByLabelText('상태').textContent).toBe('NONE')
    expect(screen.getByLabelText('자리').textContent).toBe('3')
    fireEvent.click(screen.getByText('전략 선택'))
    await waitFor(() => expect(screen.getByLabelText('상태').textContent).toBe('READY'))
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(api.create.mock.calls[0][0].conditions).toMatchObject({
      start: base.config.start,
      end: base.config.end,
      initialCash: base.config.initialCash,
      warmupTradingDays: base.config.warmupTradingDays,
      costProfileVersion: base.versions.costProfile?.version,
    })
    expect(api.create.mock.calls[0][0].strategy.parameters).toEqual(choice.parameters)
    expect(screen.getByLabelText('주소').textContent).toContain('?compare=' + targetToken)
    expect(screen.getByText('비교 곡선 사용 가능 1')).toBeTruthy()
    expect(screen.getByLabelText('자리').textContent).toBe('2')
  })
  it('여럿을 고르면 한 번에 요청하고 모두 주소에 올린 뒤 하나씩 뺄 수 있다', async () => {
    api.create
      .mockResolvedValueOnce({ publicToken: targetToken })
      .mockResolvedValueOnce({ publicToken: secondToken })
    setup()
    fireEvent.click(screen.getByText('둘 선택'))
    await waitFor(() => expect(screen.getByLabelText('상태').textContent).toBe('READY,READY'))
    expect(api.create).toHaveBeenCalledTimes(2)
    expect(api.create.mock.calls[1][0].strategy.strategyId).toBe(plainStrategy.strategyId)
    expect(screen.getByLabelText('주소').textContent).toContain(
      '?compare=' + targetToken + '%2C' + secondToken,
    )
    expect(screen.getByText('비교 곡선 사용 가능 2')).toBeTruthy()
    expect(screen.getByLabelText('비교 대상 비교용 전략')).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: '비교 해제' })[0])
    await waitFor(() =>
      expect(screen.getByLabelText('주소').textContent).toBe(
        '/results/' + base.publicToken + '?compare=' + secondToken,
      ),
    )
    expect(screen.queryByLabelText('비교 대상 비교용 전략')).toBeNull()
  })
  it('하나가 거절돼도 접수된 것은 주소에 오르고 거절은 자리에 남아 다시 요청할 수 있다', async () => {
    api.create
      .mockResolvedValueOnce({ publicToken: targetToken })
      .mockRejectedValueOnce(
        new BacktestRequestError('api', '한도', {
          code: 'EXECUTION_LIMIT',
          status: 429,
          details: { retryAfterSeconds: 30 },
        }),
      )
      .mockResolvedValueOnce({ publicToken: secondToken })
    setup()
    fireEvent.click(screen.getByText('둘 선택'))
    expect(await screen.findByText(/30초 뒤/)).toBeTruthy()
    await waitFor(() =>
      expect(screen.getByLabelText('주소').textContent).toContain('?compare=' + targetToken),
    )
    expect(screen.getByLabelText('주소').textContent).not.toContain(secondToken)
    expect(screen.getByLabelText('비교 대상 ' + plainStrategy.name)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 요청' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(3))
    await waitFor(() =>
      expect(screen.getByLabelText('주소').textContent).toContain(
        targetToken + '%2C' + secondToken,
      ),
    )
  })
  it('대기·실행 중에는 단계와 비율을 보이고 조회만 반복한다', async () => {
    target = { ...resultDetailRunning, publicToken: targetToken }
    setup('?compare=' + targetToken)
    expect(await screen.findByText(/분석 계산: 180\/380거래일/)).toBeTruthy()
    expect(api.series).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()
  })
  it('대기 중에는 서버가 알려 준 대기 시간을 보인다', async () => {
    target = {
      ...resultDetailRunning,
      publicToken: targetToken,
      status: 'QUEUED',
      queueWaitSeconds: 23,
    }
    setup('?compare=' + targetToken)
    expect(await screen.findByText(/23초 기다렸습니다/)).toBeTruthy()
  })
  it('429는 자동으로 재접수하지 않으며 다시 요청을 눌러야 한 번 더 보낸다', async () => {
    api.create.mockRejectedValueOnce(
      new BacktestRequestError('api', '한도', {
        code: 'EXECUTION_LIMIT',
        status: 429,
        details: { retryAfterSeconds: 30 },
      }),
    )
    setup()
    fireEvent.click(screen.getByText('전략 선택'))
    expect(await screen.findByText(/30초 뒤/)).toBeTruthy()
    vi.useFakeTimers()
    await act(async () => vi.advanceTimersByTime(60_000))
    expect(api.create).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
    fireEvent.click(screen.getByRole('button', { name: '다시 요청' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByLabelText('상태').textContent).toBe('READY'))
  })
  it('실패 사유를 표시하고 공개 토큰으로 명시적 재시도한다', async () => {
    target = {
      ...target,
      status: 'FAILED',
      summary: null,
      failure: { code: 'ENGINE_ERROR', message: '계산 실패' },
    }
    api.retry.mockResolvedValue({ publicToken: newTarget })
    setup('?compare=' + targetToken)
    expect(await screen.findByText('ENGINE_ERROR: 계산 실패')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(api.retry).toHaveBeenCalledWith(targetToken))
    expect(api.create).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByLabelText('주소').textContent).toContain(newTarget))
  })
  it('대상만 만료되면 기준 결과를 남긴다', async () => {
    api.detail.mockRejectedValue(new ResultError('만료', { code: 'RESULT_EXPIRED', status: 410 }))
    setup('?compare=' + targetToken)
    expect(await screen.findByText(/비교 대상 결과가 더 이상/)).toBeTruthy()
    expect(screen.getByText('기준 결과 유지')).toBeTruthy()
    expect(api.create).not.toHaveBeenCalled()
  })
  it('선택 뒤 비교 해제를 누르면 늦게 온 접수 응답으로 주소를 바꾸지 않는다', async () => {
    let resolve!: (value: { publicToken: string }) => void
    api.create.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    setup()
    fireEvent.click(screen.getByText('전략 선택'))
    fireEvent.click(await screen.findByRole('button', { name: '비교 해제' }))
    await act(async () => resolve({ publicToken: targetToken }))
    expect(screen.getByLabelText('주소').textContent).toBe('/results/' + base.publicToken)
    expect(screen.getByLabelText('상태').textContent).toBe('NONE')
    expect(api.retry).not.toHaveBeenCalled()
  })
})

describe('공통 기간 재계산 (T060)', () => {
  it('한도 뒤 명시적 재시도에서 422를 받더라도 선택한 전략을 잊지 않는다', async () => {
    api.create
      .mockRejectedValueOnce(
        new BacktestRequestError('api', '한도', {
          code: 'EXECUTION_LIMIT',
          details: { retryAfterSeconds: 1 },
        }),
      )
      .mockRejectedValueOnce(
        new BacktestRequestError('api', '준비 부족', {
          code: 'INSUFFICIENT_HISTORY',
          details: { requiredWarmupTradingDays: 95 },
        }),
      )
      .mockResolvedValueOnce({ publicToken: newBase })
      .mockResolvedValueOnce({ publicToken: newTarget })
    setup()
    fireEvent.click(screen.getByText('전략 선택'))
    fireEvent.click(await screen.findByRole('button', { name: '다시 요청' }))
    fireEvent.click(await screen.findByRole('button', { name: '두 결과 다시 계산' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(4))
    expect(api.create.mock.calls[3][0].strategy.strategyId).toBe(choice.strategy.strategyId)
    expect(api.create.mock.calls[3][0].strategy.parameters).toEqual(choice.parameters)
  })
  it('기간 불일치는 비교 곡선을 막고 취소하면 요청이 없다', async () => {
    target = {
      ...target,
      config: { ...target.config, effectiveStart: '2024-01-02', warmupTradingDays: 80 },
    }
    setup('?compare=' + targetToken)
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(screen.queryByText(/비교 곡선 사용 가능/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(api.create).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText(/평가 기간 또는 준비 기간이 달라/)).toBeTruthy()
  })
  it('더 긴 전략은 사전 확인 뒤 두 건을 같은 워밍업·기간으로 요청하고 복귀 주소를 남긴다', async () => {
    api.create
      .mockResolvedValueOnce({ publicToken: newBase })
      .mockResolvedValueOnce({ publicToken: newTarget })
    setup()
    fireEvent.click(screen.getByText('긴 전략 선택'))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(api.create).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '두 결과 다시 계산' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
    for (const [request] of api.create.mock.calls)
      expect(request.conditions).toMatchObject({
        warmupTradingDays: 80,
        start: '2024-01-02',
        end: base.config.effectiveEnd,
      })
    await waitFor(() =>
      expect(screen.getByLabelText('주소').textContent).toBe(
        '/results/' + newBase + '?compare=' + newTarget,
      ),
    )
    expect(screen.getByLabelText('복귀').textContent).toBe('/results/' + base.publicToken)
  })
  it('422가 보낸 필요값을 확인창에 넣고 자동으로 다시 요청하지 않는다', async () => {
    api.create.mockRejectedValueOnce(
      new BacktestRequestError('api', '준비 자료 부족', {
        code: 'INSUFFICIENT_HISTORY',
        status: 422,
        details: { requiredWarmupTradingDays: 95, requiredStartDate: '2024-02-01' },
      }),
    )
    setup()
    fireEvent.click(screen.getByText('전략 선택'))
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(screen.getByText('공통 준비 기간: 95거래일')).toBeTruthy()
    expect(screen.getByText('필요 시작일: 2024-02-01')).toBeTruthy()
    expect(api.create).toHaveBeenCalledTimes(1)
  })
  it('두 번째 요청의 한도 오류 뒤 재시도해도 접수된 첫 번째 요청은 반복하지 않는다', async () => {
    api.create
      .mockResolvedValueOnce({ publicToken: newBase })
      .mockRejectedValueOnce(
        new BacktestRequestError('api', '한도', {
          code: 'EXECUTION_LIMIT',
          details: { retryAfterSeconds: 30 },
        }),
      )
      .mockResolvedValueOnce({ publicToken: newTarget })
    setup()
    fireEvent.click(screen.getByText('긴 전략 선택'))
    fireEvent.click(screen.getByRole('button', { name: '두 결과 다시 계산' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(api.create).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: '두 결과 다시 계산' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(3))
    expect(api.create.mock.calls[2][0].strategy.strategyId).toBe(choice.strategy.strategyId)
    await waitFor(() => expect(screen.getByLabelText('주소').textContent).toContain(newTarget))
  })
})
