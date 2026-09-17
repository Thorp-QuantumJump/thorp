import { ApiClientError, apiClient, normalizeApiError } from './client'
import type {
  AnalysisVersions,
  CostRates,
  StrategyRulesEntry,
} from '@/components/strategy/analysisSupport'
import type { ApiEnvelope } from '@/types/api'

/**
 * 분석 요청에 실을 규칙 버전과 기본 비용 조건을 읽는 **유일한 자리**다(003 T039, "착수 전에 알아둘 것" ②).
 *
 * <p><b>출처는 004의 `GET /backtests/registry`다(2026-09-17 004 담당자 결정).</b> 004 규칙 등록표의 행 전체
 * (`strategyId`·`rulesVersion`·`status`·`provisional`)와 기본 비용 조건
 * (`defaultCostProfile { version, provisional, commissionBps, spreadBps, slippageBps, taxBps }`)을 받는다.
 * 003 등록표 응답에 싣는 안은 `strategy`가 `backtest`를 참조하게 되어, `rulesVersion`을 선택 필드로 바꾸는
 * 안은 FR-010의 비용 수치·임시 여부를 채우지 못해 버렸다.
 *
 * <p><b>2026-09-17 기준 이 엔드포인트는 dev에 없다.</b> 필드 이름(`strategyRules`·`defaultCostProfile`)은 004
 * 담당자가 확인했다. 004는 전략마다 현재 버전 한 행만 내리고, 버전이 바뀐 옛 행은 `SUPERSEDED`로 빼며,
 * `PENDING_DECISION` 행은 상태 그대로 내린다. 요청에는 `defaultCostProfile.version`을 명시해 싣는다.
 *
 * <p><b>값을 지어내지 않는다.</b> 응답이 계약과 어긋나면 일부만 살리지 않고 거부하며, 화면은 '분석하기'를
 * "분석 조건 버전을 확인할 수 없습니다"로 막는다(원칙 IV·VI).
 */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readRulesEntry(value: unknown): StrategyRulesEntry | null {
  if (!isRecord(value)) return null
  const { strategyId, rulesVersion, status, provisional } = value
  if (
    typeof strategyId !== 'string' ||
    typeof rulesVersion !== 'string' ||
    typeof status !== 'string' ||
    typeof provisional !== 'boolean'
  ) {
    return null
  }
  return { strategyId, rulesVersion, status, provisional }
}

function isRate(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

/** 응답 본문을 읽는다. 계약과 어긋나면 `null`이다. */
export function readBacktestRegistry(value: unknown): AnalysisVersions | null {
  if (!isRecord(value) || !Array.isArray(value.strategyRules)) return null
  const cost = value.defaultCostProfile
  if (!isRecord(cost) || typeof cost.version !== 'string' || typeof cost.provisional !== 'boolean') {
    return null
  }
  const { commissionBps, spreadBps, slippageBps, taxBps } = cost
  if (!isRate(commissionBps) || !isRate(spreadBps) || !isRate(slippageBps) || !isRate(taxBps)) {
    return null
  }

  const strategyRules: StrategyRulesEntry[] = []
  for (const item of value.strategyRules) {
    const entry = readRulesEntry(item)
    if (!entry) return null
    strategyRules.push(entry)
  }

  const costRates: CostRates = { commissionBps, spreadBps, slippageBps, taxBps }
  return {
    strategyRules,
    costProfileVersion: cost.version,
    costProfileProvisional: cost.provisional,
    costRates,
  }
}

const UNREADABLE_MESSAGE = '분석 조건 버전을 확인하지 못했습니다.'

/**
 * 004 규칙 등록표와 기본 비용 조건을 받는다. 실패와 계약 위반은 거부한다.
 *
 * 화면이 열릴 때 한 번 부르며, 고른 전략의 버전은 받은 목록에서 찾는다(`resolveRulesVersion`).
 */
export async function fetchAnalysisVersions(signal?: AbortSignal): Promise<AnalysisVersions | null> {
  let body: ApiEnvelope<unknown>
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>('/backtests/registry', { signal })
    body = response.data
  } catch (error) {
    throw normalizeApiError(error)
  }
  if (!body.success) throw new ApiClientError('api', body.error.message, { code: body.error.code })
  const versions = readBacktestRegistry(body.data)
  if (!versions) throw new ApiClientError('unexpected', UNREADABLE_MESSAGE)
  return versions
}
