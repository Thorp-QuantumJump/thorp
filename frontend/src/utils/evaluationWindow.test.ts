import { describe, expect, it } from 'vitest'
import {
  EVALUATION_WINDOW_UNAVAILABLE,
  type EvaluationWindowState,
} from '@/components/strategy/analysisSupport'
import { registryFixture, selectableStrategy } from '@/test/strategyRegistryFixtures'
import type { EvaluationWindow } from '@/types/evaluationWindow'
import type { RegistryResponse, StrategyView } from '@/types/strategyRegistry'
import {
  EVALUATION_WINDOW_LOADING,
  NOT_EVALUABLE,
  WARMUP_LIMIT_UNKNOWN,
  evaluationWindowQuery,
  judgeWarmupFromWindow,
  maxWarmupOfRegistry,
} from './evaluationWindow'

/** 합성 거래일 다섯 날. 주말(01-06·01-07)이 빠져 있다. */
const days = ['2024-01-02', '2024-01-03', '2024-01-04', '2024-01-05', '2024-01-08']

function windowOf(overrides: Partial<EvaluationWindow> = {}): EvaluationWindow {
  return {
    commonStart: days[2],
    commonEnd: '2026-01-05',
    warmupTradingDays: 2,
    bindingCandidate: '000001.XKRX',
    bindingSector: 'SEC-A',
    excludedRanges: [],
    evaluable: true,
    datasetId: 'TEST-DATASET-1',
    warmupBaseDate: days[0],
    tradingDaysFromBase: days,
    maxWarmupTradingDays: days.length - 1,
    excludedRangesFromBase: [],
    ...overrides,
  }
}

function loaded(
  window: EvaluationWindow = windowOf(),
  requestedMaxWarmup: number = days.length - 1,
): EvaluationWindowState {
  return { status: 'loaded', window, requestedMaxWarmup }
}

describe('받아 둔 평가 기간으로 워밍업을 판정한다(T042)', () => {
  it('워밍업 N일의 공통 시작일은 기준일부터 0-기준 N번째 거래일이다', () => {
    const judgement = judgeWarmupFromWindow(loaded(), { warmupTradingDays: 4, minEvaluationYears: 0 })

    // 주말을 건너뛰어 4번째가 01-08이다. 달력 날짜를 더했다면 01-06이 된다.
    expect(judgement.window).toEqual({ start: '2024-01-08', end: '2026-01-05' })
    expect(judgement.verdict.state).toBe('reduced')
    expect(judgement.reduction).toEqual({
      startWithoutWarmup: '2024-01-02',
      bindingCandidate: '000001.XKRX',
      bindingSector: 'SEC-A',
    })
  })

  it('워밍업이 0이면 기준일에서 시작하고 줄어들지 않는다', () => {
    const judgement = judgeWarmupFromWindow(loaded(), { warmupTradingDays: 0, minEvaluationYears: 1 })

    expect(judgement).toEqual({
      verdict: { state: 'ok' },
      window: { start: '2024-01-02', end: '2026-01-05' },
      reduction: null,
    })
  })

  it('서버가 준 commonStart가 아니라 지금 워밍업으로 다시 계산한다', () => {
    // 응답의 commonStart는 워밍업 2일 기준이다. 워밍업 1일로 판정하면 그 값을 쓰면 안 된다.
    const judgement = judgeWarmupFromWindow(loaded(), { warmupTradingDays: 1, minEvaluationYears: 0 })
    expect(judgement.window?.start).toBe('2024-01-03')
  })

  it('남는 기간이 최소 평가 기간에 못 미치면 막고, 정확히 채우면 막지 않는다', () => {
    const window = windowOf({ commonEnd: '2025-01-03' })

    const exact = judgeWarmupFromWindow(loaded(window), { warmupTradingDays: 1, minEvaluationYears: 1 })
    expect(exact.verdict.state).toBe('reduced')

    const short = judgeWarmupFromWindow(loaded(window), { warmupTradingDays: 2, minEvaluationYears: 1 })
    expect(short.verdict).toEqual({
      state: 'blocked',
      message: '워밍업을 채우고 남는 기간이 최소 평가 기간(1년)에 못 미칩니다.',
    })
    expect(short.window).toBeNull()

    // 제한 없음(0)이면 같은 기간도 막지 않는다.
    const unlimited = judgeWarmupFromWindow(loaded(window), { warmupTradingDays: 2, minEvaluationYears: 0 })
    expect(unlimited.verdict.state).toBe('reduced')
  })

  it('워밍업을 채운 날이 commonEnd보다 늦으면 분석할 수 없다', () => {
    const window = windowOf({ commonEnd: '2024-01-04' })

    expect(judgeWarmupFromWindow(loaded(window), { warmupTradingDays: 2, minEvaluationYears: 0 }).verdict.state).toBe(
      'reduced',
    )
    expect(judgeWarmupFromWindow(loaded(window), { warmupTradingDays: 3, minEvaluationYears: 0 })).toEqual({
      verdict: { state: 'blocked', message: NOT_EVALUABLE },
      window: null,
      reduction: null,
    })
  })

  it('워밍업이 요청한 상한을 넘으면 날짜를 지어내지 않고 판정하지 못한다', () => {
    const judgement = judgeWarmupFromWindow(loaded(), { warmupTradingDays: 5, minEvaluationYears: 0 })

    expect(judgement.verdict.state).toBe('unknown')
    expect(judgement.window).toBeNull()
  })

  it('요청한 상한 안인데 달력이 먼저 끝났으면 그 워밍업으로는 분석할 수 없다', () => {
    // 90거래일까지 달라고 했는데 001이 4거래일까지만 줬다. 목록은 commonEnd까지이므로 5일째 평가할 날이 없다.
    const window = windowOf({ commonEnd: days[4] })

    expect(
      judgeWarmupFromWindow(loaded(window, 90), { warmupTradingDays: 4, minEvaluationYears: 0 }).verdict.state,
    ).toBe('reduced')
    expect(judgeWarmupFromWindow(loaded(window, 90), { warmupTradingDays: 5, minEvaluationYears: 0 })).toEqual({
      verdict: { state: 'blocked', message: NOT_EVALUABLE },
      window: null,
      reduction: null,
    })
  })

  it('기준일이 이미 commonEnd를 넘어 목록이 비었으면 워밍업 0도 분석할 수 없다', () => {
    const window = windowOf({
      commonStart: null,
      commonEnd: '2023-12-29',
      evaluable: false,
      tradingDaysFromBase: [],
      maxWarmupTradingDays: null,
    })

    expect(
      judgeWarmupFromWindow(loaded(window, 90), { warmupTradingDays: 0, minEvaluationYears: 0 }).verdict,
    ).toEqual({ state: 'blocked', message: NOT_EVALUABLE })
  })

  it('받기 전·받지 못함·상한 계산 불가는 모두 판정하지 못한 상태다', () => {
    const input = { warmupTradingDays: 0, minEvaluationYears: 0 }

    expect(judgeWarmupFromWindow({ status: 'loading' }, input).verdict).toEqual({
      state: 'unknown',
      message: EVALUATION_WINDOW_LOADING,
    })
    expect(judgeWarmupFromWindow({ status: 'unavailable' }, input).verdict).toEqual({
      state: 'unknown',
      message: EVALUATION_WINDOW_UNAVAILABLE,
    })
    expect(judgeWarmupFromWindow({ status: 'uncomputable' }, input).verdict).toEqual({
      state: 'unknown',
      message: WARMUP_LIMIT_UNKNOWN,
    })
  })
})

describe('등록표에서 워밍업 상한을 계산한다(T042)', () => {
  it('전략마다 규칙에 축별 최대값을 넣은 합 가운데 가장 큰 값이다', () => {
    // 표류 균형 맞추기: driftWindow 최대 90. 맥박 따라 옮기기: 60 + 10 = 70. 워밍업 없는 전략: 0.
    expect(maxWarmupOfRegistry(registryFixture)).toBe(90)
  })

  it('입력형 축은 range.max를, 상수 항목은 그 값을 더한다', () => {
    const strategy: StrategyView = {
      ...selectableStrategy,
      axes: [
        ...selectableStrategy.axes,
        {
          name: 'lookback',
          label: '관측',
          inputType: 'NUMBER',
          range: { min: 10, max: 250, step: 10 },
          defaultValue: 60,
        },
      ],
      warmupRule: { sum: ['lookback', 'driftWindow', 5] },
    }
    const registry: RegistryResponse = { ...registryFixture, strategies: [strategy] }

    expect(maxWarmupOfRegistry(registry)).toBe(250 + 90 + 5)
  })

  it('규칙이 가리키는 축에서 수 후보를 찾지 못하면 모자란 상한을 짓지 않는다', () => {
    const strategy: StrategyView = { ...selectableStrategy, warmupRule: { sum: ['resetTiming'] } }
    const registry: RegistryResponse = {
      ...registryFixture,
      strategies: [...registryFixture.strategies, { ...strategy, strategyId: 'BROKEN' }],
    }

    expect(maxWarmupOfRegistry(registry)).toBeNull()
    expect(evaluationWindowQuery(['A', 'B'], registry)).toBe('uncomputable')
  })

  it('조회에는 고른 순서 그대로의 섹터 코드와 상한을 싣고 전략은 싣지 않는다', () => {
    // 001 산식은 동점일 때 입력 순서로 bindingCandidate를 고른다. 정렬하면 접수 경로와 원인이 갈릴 수 있다.
    expect(evaluationWindowQuery(['SEC-B', 'SEC-A'], registryFixture)).toEqual({
      sectors: ['SEC-B', 'SEC-A'],
      maxWarmupTradingDays: 90,
    })
  })
})
