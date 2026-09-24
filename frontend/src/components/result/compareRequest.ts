import type { AxisValues } from '@/hooks/useStrategySettings'
import type { BacktestCreateRequest, BacktestScenario } from '@/types/backtestRequest'
import type { PublicResultDetail } from '@/types/result'
import type { AxisValue, StrategyView } from '@/types/strategyRegistry'

export type CompareChoice = {
  period?: { start: string; end: string }
  strategy: StrategyView
  /** 빈 문자열이면 요청에 싣지 않는다 — 등록표 버전 없이 접수된 기준을 그대로 따라갈 때다 */
  registryVersion: string
  rulesVersion: string
  /**
   * 상대편에 붙일 설정값. 등록표 기본값이거나 내가 써 본 조합이다(`S15P21E205-419`). 후보를 만드는 쪽이 채우므로
   * 요청을 조립하는 이 파일은 어느 쪽인지 모른다.
   */
  parameters: AxisValues
  /**
   * 변동성 제한만 바꿔 보는 후보. 있으면 기준의 시나리오 대신 이것을 싣는다 — `TARGET_RISK`면 목표 변동성(연, 퍼센트
   * 숫자)을 함께, `FULL_PERIOD`면 제한 없이. 없으면 기준 결과의 시나리오를 그대로 따른다.
   */
  volatilityLimit?: { scenario: 'TARGET_RISK'; targetVolatility: number } | { scenario: 'FULL_PERIOD' }
}

/**
 * 공개 config → 004 요청의 대응은 이곳에만 둔다. 비용은 config에 없으므로 versions에서 읽는다.
 * choice가 없으면 원래 설정을 재현한다. 새 전략의 설정값은 choice가 들고 오고 규칙 버전은 등록표가 소유한다.
 */
export function buildCompareRequest(
  base: PublicResultDetail,
  choice: CompareChoice | null,
  warmupTradingDays = base.config.warmupTradingDays,
  period?: { start: string; end: string },
): BacktestCreateRequest {
  const { config, versions } = base
  if (!versions.costProfile)
    throw new Error('기준 결과의 비용 기준을 확인할 수 없어 같은 조건으로 요청할 수 없습니다.')
  const baseScenario = config.scenario as BacktestScenario
  if (!['FULL_PERIOD', 'FRESH_CASH_FROM', 'COST_ESCALATION', 'TARGET_RISK'].includes(baseScenario)) {
    throw new Error('기준 결과의 분석 시나리오를 확인할 수 없습니다.')
  }
  const limit = choice?.volatilityLimit
  const scenario = limit?.scenario ?? baseScenario
  const scenarioParams = !limit
    ? config.scenarioParams
    : limit.scenario === 'TARGET_RISK'
      ? { targetVolatility: (limit.targetVolatility / 100).toFixed(4) }
      : undefined
  const userWeights = choice
    ? choice.strategy.targetWeightSource === 'USER'
    : base.weightsSource === 'USER'
  if (userWeights && config.items.some((item) => item.targetWeight == null)) {
    throw new Error(
      '이 결과에는 입력한 목표 비중이 없습니다. 처음부터 다시 분석에서 비중을 먼저 정해 주세요.',
    )
  }
  return {
    executionType: 'SINGLE',
    sectorAllocation: {
      mappingVersion: config.mappingVersion,
      items: !choice
        ? config.items.map((item) => ({ ...item }))
        : config.items.map(({ sectorId, instrumentId, targetWeight }) => ({
            sectorId,
            instrumentId,
            ...(userWeights ? { targetWeight } : {}),
          })),
    },
    strategy: {
      strategyId: choice?.strategy.strategyId ?? config.strategyId,
      rulesVersion: choice?.rulesVersion ?? config.rulesVersion,
      // 등록표 버전이 빈 후보는 등록표 없이 접수된 기준을 그대로 따라가는 것이다(변동성 제한 줄). 없는 버전을 붙이지 않는다.
      ...(choice
        ? choice.registryVersion
          ? { registryVersion: choice.registryVersion }
          : {}
        : config.registryVersion
          ? { registryVersion: config.registryVersion }
          : {}),
      parameters: choice
        ? { ...choice.parameters }
        : ({ ...config.parameters } as Record<string, AxisValue>),
    },
    conditions: {
      start: period?.start ?? config.start,
      end: period?.end ?? config.end,
      initialCash: config.initialCash,
      costProfileVersion: versions.costProfile.version,
      warmupTradingDays,
      scenario,
      evaluationSplitVersion: config.evaluationSplitVersion,
      ...(scenarioParams ? { scenarioParams: { ...scenarioParams } } : {}),
    },
  }
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object')
    return JSON.stringify(
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, item]) => [key, canonical(item)]),
    )
  return JSON.stringify(value)
}

export function comparisonMismatch(
  base: PublicResultDetail,
  target: PublicResultDetail,
): 'conditions' | 'versions' | 'period' | null {
  const comparable = (result: PublicResultDetail) => ({
    items: result.config.items
      .map(({ sectorId, instrumentId }) => sectorId + ':' + instrumentId)
      .sort(),
    initialCash: result.config.initialCash
      .replace(/^0+(?=\d)/, '')
      .replace(/(\.\d*?)0+$/, '$1')
      .replace(/\.$/, ''),
    currency: result.config.currency,
    mapping: result.config.mappingVersion,
    cost: result.versions.costProfile?.version,
    evaluationSplit: result.config.evaluationSplitVersion ?? null,
    // 변동성 제한은 비교 대상 고르기가 바꿔 보는 축이라 조건 차이로 보지 않는다. 다른 시나리오는 그대로 조건이다.
    scenario: result.config.scenario === 'TARGET_RISK' ? 'FULL_PERIOD' : result.config.scenario,
    scenarioParams:
      result.config.scenario === 'TARGET_RISK' ? {} : (result.config.scenarioParams ?? {}),
  })
  if (
    !base.versions.costProfile ||
    !target.versions.costProfile ||
    canonical(comparable(base)) !== canonical(comparable(target))
  )
    return 'conditions'
  if (
    !base.versions.data ||
    !target.versions.data ||
    !base.versions.engine ||
    !target.versions.engine ||
    base.versions.data !== target.versions.data ||
    base.versions.engine !== target.versions.engine
  )
    return 'versions'
  if (
    base.partial ||
    target.partial ||
    base.config.effectiveStart !== target.config.effectiveStart ||
    base.config.effectiveEnd !== target.config.effectiveEnd ||
    base.config.warmupTradingDays !== target.config.warmupTradingDays
  )
    return 'period'
  return null
}
