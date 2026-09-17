import { fetchSectorCatalog } from '@/api/sectors'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import {
  SECTOR_SELECTION_STORAGE_KEY,
  type SectorSelectionSnapshot,
  writeStoredSelection,
} from '@/diagnosis/selectionStorage'
import { distributeEven } from '@/diagnosis/weights'
import {
  type AxisValues,
  type StoredStrategySettings,
  isAxisValue,
  reconcileStoredSettings,
  writeStoredStrategySettings,
} from '@/hooks/useStrategySettings'
import type { ResultConfig } from '@/types/result'
import type { SectorCatalog } from '@/types/sector'
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
 * <p><b>결과에 없는 값은 003이 처음 열릴 때와 같이 둔다.</b> 요청 사본에는 최소 평가 기간이 없어 등록표 기본값을
 * 넣는다. 그 값으로 기간이 짧다고 판정되면 003 화면이 그 사실을 알린다.
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

/** 003 설정 화면이 다루는 분석 방식. 목표 위험 맞춤 같은 별도 시나리오는 그 화면의 상태에 없다 */
const MAIN_SCENARIO = 'FULL_PERIOD'

const REGISTRY_FAILED = '설정 화면에 넘길 전략 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const STORAGE_FAILED =
  '이 브라우저에서는 조건을 설정 화면으로 넘길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'
const CONFIG_UNREADABLE =
  '이 결과의 조건을 설정 화면이 읽는 모양으로 옮길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'
const SCENARIO_UNSUPPORTED =
  '이 결과는 별도 시나리오로 계산한 결과라 설정 화면으로 조건을 넘길 수 없습니다. 원래 결과에서 다시 시작해 주세요.'

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

function carriesUserWeights(config: ResultConfig): boolean {
  return config.items.every((item) => item.targetWeight !== undefined)
}

/**
 * 002의 선택 보관 모양. 모든 섹터에 사용자 비중이 실려 있으면 그 값을, 아니면(규칙이 비중을 계산한 전략)
 * 002가 손대지 않은 선택에 주는 동일 비중을 넣는다.
 *
 * @param catalog 동일 비중의 잔여분을 002 표시 순서의 첫 섹터에 붙이려고 쓴다(`distributeEven`). 없으면 결과의
 *   순서를 쓴다 — 002는 손대지 않은 선택에서 섹터를 더하거나 빼면 다시 나누므로 잔여분 자리만 다를 수 있다
 */
export function buildSelectionSnapshot(
  config: ResultConfig,
  catalog: SectorCatalog | null = null,
): SectorSelectionSnapshot {
  const codes = config.items.map((item) => item.sectorId)
  const userWeights = carriesUserWeights(config)

  let evenOrder = codes
  if (!userWeights && catalog) {
    const rank = new Map(
      sortSectorsForDisplay(catalog.sectors).map((sector, index) => [sector.code, index] as const),
    )
    // 지금 목록에 없는 섹터는 뒤에 둔다. 002가 읽을 때 그 섹터를 빠진 것으로 알린다.
    evenOrder = [...codes].sort(
      (left, right) =>
        (rank.get(left) ?? Number.MAX_SAFE_INTEGER) - (rank.get(right) ?? Number.MAX_SAFE_INTEGER),
    )
  }

  return {
    mapVersion: config.mappingVersion,
    selectedCodes: codes,
    weightTenths: userWeights
      ? Object.fromEntries(
          config.items.map((item) => [item.sectorId, toWeightTenths(item.targetWeight ?? '')]),
        )
      : distributeEven(evenOrder),
    weightsTouched: userWeights,
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

function readRaw(key: string): string | null {
  try {
    return window.sessionStorage.getItem(key)
  } catch {
    return null
  }
}

/** 앞서 있던 값으로 되돌린다. 되돌리기도 실패하면 더 할 수 있는 일이 없다 */
function restoreRaw(key: string, raw: string | null): void {
  try {
    if (raw === null) window.sessionStorage.removeItem(key)
    else window.sessionStorage.setItem(key, raw)
  } catch {
    // 저장소가 막혀 있으면 앞선 쓰기도 실패했을 것이다.
  }
}

/** 등록표 조회와 함께 보내는 섹터 목록. 잔여분 자리를 정하는 데만 쓰므로 받지 못해도 멈추지 않는다 */
async function loadCatalogForOrder(signal?: AbortSignal): Promise<SectorCatalog | null> {
  const result = await fetchSectorCatalog(signal)
  return result.status === 'loaded' ? result.catalog : null
}

/**
 * 결과의 조건을 보관하고 설정 화면으로 옮긴다. 넘기지 못하면 옮기지 않고, 보관해 둔 002 선택도 앞선 값으로
 * 되돌린다 — 빈 설정 화면은 조건이 채워진 화면으로 읽히고, 반쯤 덮인 선택은 사용자가 고르던 것을 지운다.
 *
 * <p>등록표는 사용자가 누를 때 받는다. 결과 첫 화면은 등록표를 부르지 않는다(SC-001).
 */
export async function prefillStrategySettings(
  config: ResultConfig,
  navigate: (path: string) => void,
  signal?: AbortSignal,
): Promise<PrefillOutcome> {
  if (config.scenario !== MAIN_SCENARIO) return { status: 'failed', message: SCENARIO_UNSUPPORTED }

  let registry: RegistryResponse
  let catalog: SectorCatalog | null = null
  try {
    const [loadedRegistry, loadedCatalog] = await Promise.all([
      fetchStrategyRegistry(config.items.length, signal),
      carriesUserWeights(config) ? Promise.resolve(null) : loadCatalogForOrder(signal),
    ])
    registry = loadedRegistry
    catalog = loadedCatalog
  } catch (error) {
    if (normalizeStrategyRegistryError(error).canceled) return { status: 'canceled' }
    return { status: 'failed', message: REGISTRY_FAILED }
  }
  if (signal?.aborted) return { status: 'canceled' }

  const settings = buildStrategySettings(config, registry)
  if (settings === null) return { status: 'failed', message: CONFIG_UNREADABLE }

  const previousSelection = readRaw(SECTOR_SELECTION_STORAGE_KEY)
  if (!writeStoredSelection(buildSelectionSnapshot(config, catalog))) {
    return { status: 'failed', message: STORAGE_FAILED }
  }
  if (!writeStoredStrategySettings(settings)) {
    restoreRaw(SECTOR_SELECTION_STORAGE_KEY, previousSelection)
    return { status: 'failed', message: STORAGE_FAILED }
  }

  navigate(STRATEGY_SETTINGS_PATH)
  return { status: 'moved' }
}
