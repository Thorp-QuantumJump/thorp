import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import { type SectorSelectionSnapshot, writeStoredSelection } from '@/diagnosis/selectionStorage'
import { distributeEven } from '@/diagnosis/weights'
import {
  type AxisValues,
  type StoredStrategySettings,
  isAxisValue,
  reconcileStoredSettings,
  writeStoredStrategySettings,
} from '@/hooks/useStrategySettings'
import type { ResultConfig } from '@/types/result'
import type { RegistryResponse } from '@/types/strategyRegistry'

/**
 * '다른 조건으로 비교'(spec 005 FR-010, T068). 결과의 조건을 설정 화면에 채워 넘긴다.
 *
 * <p>넘기는 수단은 002와 003이 이미 쓰는 탭 세션 저장소다. 002의 섹터 선택(`writeStoredSelection`)과 003의
 * 전략 설정(`writeStoredStrategySettings`)을 이 결과의 `config`로 쓰고 `/strategy`로 옮긴다. 거기서 분석하면
 * 새 분석으로 접수된다 — 이 결과는 바뀌지 않는다.
 *
 * <p><b>값을 고치지 않는다.</b> 등록표 범위 밖이 된 값, 지금 목록에 없는 섹터를 여기서 걸러 내지 않는다. 003
 * 화면(`reconcileStoredSettings`)과 002 화면(`readStoredSelection`)이 다시 판정하고 무엇이 왜 빠졌는지 알린다.
 * 여기서 맞추면 사용자는 결과의 조건이 바뀐 줄 모른다(원칙 VI).
 *
 * <p>006의 '설정 바꿔 비교'도 이 함수를 쓴다(FR-019).
 */

export const STRATEGY_SETTINGS_PATH = '/strategy'

export type PrefillOutcome =
  | { status: 'moved' }
  /** 넘기지 못했다. 이동하지 않고 이 문장을 결과 화면에 보인다 */
  | { status: 'failed'; message: string }
  /** 화면을 떠나 요청을 끊었다. 사용자에게 보일 실패가 아니다 */
  | { status: 'canceled' }

const REGISTRY_FAILED = '설정 화면에 넘길 전략 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const STORAGE_FAILED =
  '이 브라우저에서는 조건을 설정 화면으로 넘길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'
const CONFIG_UNREADABLE =
  '이 결과의 조건을 설정 화면이 읽는 모양으로 옮길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'

/**
 * 계약의 비중 문자열을 002의 0.1%p 정수로 옮긴다. `"0.4000"` → `400`.
 *
 * <p>나눗셈·곱셈을 쓰지 않는다(`utils/analysisRequest.ts`의 `formatWeightTenths`를 거꾸로 한 것이다). 0.1%p보다
 * 잘게 나뉜 값이나 읽을 수 없는 값은 반올림하지 않고 `NaN`으로 둔다 — 002가 그 섹터를 '비중을 쓸 수 없음'으로
 * 알린다.
 */
export function toWeightTenths(targetWeight: string): number {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(targetWeight.trim())
  if (!match) return Number.NaN
  const [, whole, fraction = ''] = match
  if (/[1-9]/.test(fraction.slice(3))) return Number.NaN
  return Number(whole) * 1000 + Number(fraction.slice(0, 3).padEnd(3, '0'))
}

/**
 * 002의 선택 보관 모양. 모든 섹터에 사용자 비중이 실려 있으면 그 값을, 아니면(규칙이 비중을 계산한 전략)
 * 002가 손대지 않은 선택에 주는 동일 비중을 넣는다.
 */
export function buildSelectionSnapshot(config: ResultConfig): SectorSelectionSnapshot {
  const codes = config.items.map((item) => item.sectorId)
  const carriesUserWeights = config.items.every((item) => item.targetWeight !== undefined)

  return {
    mapVersion: config.mappingVersion,
    selectedCodes: codes,
    weightTenths: carriesUserWeights
      ? Object.fromEntries(
          config.items.map((item) => [item.sectorId, toWeightTenths(item.targetWeight ?? '')]),
        )
      : distributeEven(codes),
    weightsTouched: carriesUserWeights,
  }
}

/**
 * 003의 설정 보관 모양. 결과에 없는 값(최소 평가 기간)은 등록표 기본값으로 둔다 — 003이 처음 열릴 때와 같다.
 *
 * @returns 결과의 조건을 옮길 수 없으면 `null`
 */
export function buildStrategySettings(
  config: ResultConfig,
  registry: RegistryResponse,
): StoredStrategySettings | null {
  const values: AxisValues = {}
  for (const [name, value] of Object.entries(config.parameters)) {
    // 003이 읽지 못하는 모양이 섞이면 003은 보관값 전체를 버린다. 그 사실을 여기서 먼저 알린다.
    if (!isAxisValue(value)) return null
    values[name] = value
  }

  const initialAmount = Number(config.initialCash)
  if (config.initialCash.trim() === '' || !Number.isFinite(initialAmount)) return null

  // 보관값이 없을 때 003이 만드는 처음 상태다. 여기에 결과의 조건을 얹는다.
  const fresh = reconcileStoredSettings(null, registry, config.items.length)

  return {
    ...fresh,
    selectedStrategyId: config.strategyId,
    valuesByStrategy: { [config.strategyId]: values },
    analysisCondition: {
      ...fresh.analysisCondition,
      periodStart: config.start,
      periodEnd: config.end,
      initialAmount,
    },
  }
}

/**
 * 결과의 조건을 보관하고 설정 화면으로 옮긴다. 넘기지 못하면 옮기지 않는다 — 빈 설정 화면은 조건이 채워진
 * 화면으로 읽힌다.
 *
 * <p>등록표는 사용자가 누를 때 받는다. 결과 첫 화면은 등록표를 부르지 않는다(SC-001).
 */
export async function prefillStrategySettings(
  config: ResultConfig,
  navigate: (path: string) => void,
  signal?: AbortSignal,
): Promise<PrefillOutcome> {
  let registry: RegistryResponse
  try {
    registry = await fetchStrategyRegistry(config.items.length, signal)
  } catch (error) {
    if (normalizeStrategyRegistryError(error).canceled) return { status: 'canceled' }
    return { status: 'failed', message: REGISTRY_FAILED }
  }

  const settings = buildStrategySettings(config, registry)
  if (settings === null) return { status: 'failed', message: CONFIG_UNREADABLE }

  if (!writeStoredSelection(buildSelectionSnapshot(config))) {
    return { status: 'failed', message: STORAGE_FAILED }
  }
  if (!writeStoredStrategySettings(settings)) {
    return { status: 'failed', message: STORAGE_FAILED }
  }

  navigate(STRATEGY_SETTINGS_PATH)
  return { status: 'moved' }
}
