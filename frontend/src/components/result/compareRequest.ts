import { defaultValuesOf } from '@/hooks/useStrategySettings'
import type { BacktestCreateRequest, BacktestScenario } from '@/types/backtestRequest'
import type { PublicResultDetail } from '@/types/result'
import type { AxisValue, StrategyView } from '@/types/strategyRegistry'

export type CompareChoice = {
  period?: { start: string; end: string }
  strategy: StrategyView
  registryVersion: string
  rulesVersion: string
}

/**
 * 공개 config → 004 요청의 대응은 이곳에만 둔다. 비용은 config에 없으므로 versions에서 읽는다.
 * choice가 없으면 원래 설정을 재현한다. 새 전략의 기본값과 규칙 버전은 등록표가 소유한다.
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
  const scenario = config.scenario as BacktestScenario
  if (!['FULL_PERIOD', 'FRESH_CASH_FROM', 'COST_ESCALATION', 'TARGET_RISK'].includes(scenario)) {
    throw new Error('기준 결과의 분석 시나리오를 확인할 수 없습니다.')
  }
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
      ...(choice
        ? { registryVersion: choice.registryVersion }
        : config.registryVersion
          ? { registryVersion: config.registryVersion }
          : {}),
      parameters: choice
        ? defaultValuesOf(choice.strategy)
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
      ...(config.scenarioParams ? { scenarioParams: { ...config.scenarioParams } } : {}),
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
    scenario: result.config.scenario,
    scenarioParams: result.config.scenarioParams ?? {},
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
