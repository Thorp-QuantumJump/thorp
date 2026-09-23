import type { SectorSelection } from '@/hooks/useSectorSelection'
import type { BacktestCreateRequest, BacktestScenario } from '@/types/backtestRequest'
import type { AxisValue, StrategyView } from '@/types/strategyRegistry'

/**
 * 분석 요청을 조립하는 순수 함수(data-model §10, 계약 §2).
 *
 * <p>화면 상태와 002·004에서 받은 값을 `POST /backtests` 요청 모양으로 옮기기만 한다. <b>여기서
 * 값을 만들거나 고치지 않는다</b> — 비어 있는 값은 호출자가 먼저 막아야 하며, 이 함수는 받은 것을
 * 그대로 싣는다(원칙 VI).
 */

/** 요청에 실을 규칙·비용 버전. 출처는 004와 함께 정한다("착수 전에 알아둘 것" ②). */
export type AnalysisVersionsForRequest = {
  rulesVersion: string
  costProfileVersion: string | null
}

export type AnalysisRequestInput = {
  selection: SectorSelection
  strategy: StrategyView
  /** 축 이름 → 현재 값. 모든 축이 채워져 있어야 한다 */
  parameters: Record<string, AxisValue>
  registryVersion: string
  warmupTradingDays: number
  versions: AnalysisVersionsForRequest
  period: { start: string; end: string }
  /** 원 단위 금액 */
  initialAmount: number
  scenario: BacktestScenario
}

/**
 * 0.1%p 단위 정수 비중을 계약의 decimal 문자열로 옮긴다. `400` → `"0.4000"`, `1000` → `"1.0000"`.
 *
 * 나눗셈을 쓰지 않는다. 정수 두 자리를 문자열로 이어 붙여야 `333`이 `"0.3330"`처럼 정확히 남는다.
 */
export function formatWeightTenths(weightTenths: number): string {
  if (!Number.isInteger(weightTenths) || weightTenths < 0) {
    throw new RangeError(`비중은 0 이상의 정수(0.1%p)여야 합니다: ${weightTenths}`)
  }
  const whole = Math.floor(weightTenths / 1000)
  const fraction = String(weightTenths % 1000).padStart(3, '0')
  return `${whole}.${fraction}0`
}

/** 원 단위 금액을 계약의 decimal 문자열(소수 둘째 자리)로 옮긴다. `10000000` → `"10000000.00"`. */
export function formatInitialCash(amount: number): string {
  if (!Number.isFinite(amount) || amount <= 0) {
    throw new RangeError(`시작 금액은 0보다 큰 수여야 합니다: ${amount}`)
  }
  return amount.toFixed(2)
}

/**
 * 003이 채우는 필드로 `POST /backtests` 요청을 만든다.
 *
 * <p>`targetWeight`는 <b>등록표가 `targetWeightSource: USER`라고 말한 전략에서만</b> 싣는다. 전략
 * ID로 분기하지 않는다(FR-005). 다른 전략에 실으면 서버가 400으로 거절한다.
 */
export function buildBacktestRequest(input: AnalysisRequestInput): BacktestCreateRequest {
  const carriesUserWeights = input.strategy.targetWeightSource === 'USER'

  return {
    executionType: 'SINGLE',
    sectorAllocation: {
      mappingVersion: input.selection.mapVersion,
      items: input.selection.items.map((item) =>
        carriesUserWeights
          ? {
              sectorId: item.sectorId,
              instrumentId: item.instrumentId,
              targetWeight: formatWeightTenths(item.weightTenths),
            }
          : { sectorId: item.sectorId, instrumentId: item.instrumentId },
      ),
    },
    strategy: {
      strategyId: input.strategy.strategyId,
      rulesVersion: input.versions.rulesVersion,
      registryVersion: input.registryVersion,
      parameters: { ...input.parameters },
    },
    conditions: {
      start: input.period.start,
      end: input.period.end,
      initialCash: formatInitialCash(input.initialAmount),
      costProfileVersion: input.versions.costProfileVersion,
      warmupTradingDays: input.warmupTradingDays,
      scenario: input.scenario,
    },
  }
}
