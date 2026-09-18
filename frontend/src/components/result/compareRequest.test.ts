import { describe, expect, it } from 'vitest'
import { resultDetailCompleted as base } from '@/test/resultFixtures'
import { selectableStrategy } from '@/test/strategyRegistryFixtures'
import { buildCompareRequest, comparisonMismatch } from './compareRequest'

const choice = {
  strategy: selectableStrategy,
  registryVersion: 'registry-new',
  rulesVersion: 'rules-new',
}
describe('동일 조건 요청', () => {
  it('비용 버전과 평가 조건은 보존하고 새 전략의 기본 설정과 규칙 버전을 쓴다', () => {
    const request = buildCompareRequest(base, choice)
    expect(request.sectorAllocation.items).toEqual(
      base.config.items.map(({ sectorId, instrumentId }) => ({ sectorId, instrumentId })),
    )
    expect(request.conditions).toMatchObject({
      start: base.config.start,
      end: base.config.end,
      initialCash: base.config.initialCash,
      costProfileVersion: base.versions.costProfile?.version,
      warmupTradingDays: base.config.warmupTradingDays,
      evaluationSplitVersion: base.config.evaluationSplitVersion,
      scenario: base.config.scenario,
    })
    expect(request.strategy).toMatchObject({
      strategyId: selectableStrategy.strategyId,
      rulesVersion: 'rules-new',
      registryVersion: 'registry-new',
      parameters: { driftWindow: 45 },
    })
    expect(base.config.items[0].targetWeight).toBeDefined()
  })
  it('등록표 없이 결과 자체를 재요청할 때 설정·시나리오를 보존한다', () => {
    const request = buildCompareRequest(
      {
        ...base,
        config: {
          ...base.config,
          scenario: 'TARGET_RISK',
          scenarioParams: { targetVolatility: '0.1' },
        },
      },
      null,
      90,
    )
    expect(request.strategy.parameters).toEqual(base.config.parameters)
    expect(request.conditions.scenarioParams).toEqual({ targetVolatility: '0.1' })
    expect(request.conditions.warmupTradingDays).toBe(90)
  })
  it('비용 기준이 없으면 현재 기본 비용으로 바꾸지 않고 막는다', () => {
    expect(() =>
      buildCompareRequest({ ...base, versions: { ...base.versions, costProfile: null } }, choice),
    ).toThrow()
  })
  it('기간과 금액·비용·자료 기준 불일치를 나눠 겹치기를 막는다', () => {
    expect(comparisonMismatch(base, structuredClone(base))).toBeNull()
    expect(
      comparisonMismatch(base, {
        ...base,
        config: { ...base.config, effectiveStart: '2024-01-02' },
      }),
    ).toBe('period')
    expect(
      comparisonMismatch(base, { ...base, config: { ...base.config, initialCash: '1' } }),
    ).toBe('conditions')
    expect(
      comparisonMismatch(base, { ...base, versions: { ...base.versions, data: 'other' } }),
    ).toBe('versions')
    expect(
      comparisonMismatch(base, { ...base, versions: { ...base.versions, engine: null } }),
    ).toBe('versions')
  })
  it('같은 금액의 소수 표기는 같지만 정밀도가 다른 금액은 같다고 처리하지 않는다', () => {
    expect(
      comparisonMismatch(base, {
        ...base,
        config: { ...base.config, initialCash: '010000000.000' },
      }),
    ).toBeNull()
    const huge = { ...base, config: { ...base.config, initialCash: '9007199254740992.01' } }
    expect(
      comparisonMismatch(huge, {
        ...huge,
        config: { ...huge.config, initialCash: '9007199254740992.02' },
      }),
    ).toBe('conditions')
  })
})
