import { fetchSectorCatalog } from '@/api/sectors'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import {
  SECTOR_SELECTION_STORAGE_KEY,
  type SectorSelectionSnapshot,
  writeStoredSelection,
} from '@/diagnosis/selectionStorage'
import { distributeEven, isValidWeightTenths } from '@/diagnosis/weights'
import { MINIMUM_SECTOR_COUNT } from '@/hooks/useSectorSelection'
import {
  type AxisValues,
  type StoredStrategySettings,
  isAxisValue,
  readStoredStrategySettings,
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
 * <p><b>섹터는 설정 화면이 이어받을 수 있을 때만 넘긴다.</b> 003은 002 선택을 `useSectorSelection`으로 읽어 섹터가
 * 둘 미만이거나 대응표 버전이 지금과 다르면 조건을 이어받지 않고 앞 단계로 되돌린다. 다른 대응표에서 만든 선택을
 * 지금 버전으로 옮기지도 않는다(원칙 VIII). 그런 결과는 002 선택을 건드리지 않고, 대응표와 무관한 003 설정
 * (전략·설정값·기간·금액)만 보관한 뒤 `reselectSectors`로 알린다. 사용자가 섹터·비중 단계에서 섹터만 다시 고르고
 * 설정 화면으로 가면, 003이 새 섹터 수로 보관값을 다시 판정해(`reconcileStoredSettings`) 나머지 조건이 이어진다.
 * 섹터까지 넘기려면 002의 선택 인계 계약을 바꿔야 한다.
 *
 * <p><b>값을 고치지 않는다.</b> 등록표 범위 밖이 된 값을 여기서 걸러 내지 않는다. 003 화면
 * (`reconcileStoredSettings`)이 다시 판정하고 무엇이 왜 빠졌는지 알린다. 여기서 맞추면 사용자는 결과의 조건이
 * 바뀐 줄 모른다(원칙 VI). 같은 탭에서 다른 전략에 맞춰 둔 설정값도 지우지 않고 그대로 둔다.
 *
 * <p><b>결과에 없는 값은 앞서 고른 값이나 등록표 기본값으로 둔다.</b> 요청 사본에는 최소 평가 기간이 없어, 이
 * 탭에서 고른 값이 있으면 그것을, 없으면 003이 처음 열릴 때와 같은 등록표 기본값을 넣는다.
 *
 * <p>006의 '설정 바꿔 비교'도 이 함수를 쓴다(FR-019) — `components/records/reopenSettings.ts`가 기록 상세의
 * `reopenPayload`(같은 `ResultConfig` 모양)를 그대로 넘긴다. 006은 빌더를 따로 두지 않는다.
 */

export const STRATEGY_SETTINGS_PATH = '/strategy'

/** 002 섹터·비중 단계. 설정 화면이 조건을 이어받을 수 없을 때 사용자가 섹터를 다시 고르는 자리다 */
export const SECTOR_SELECTION_PATH = '/diagnosis/sectors'

export type PrefillOutcome =
  | { status: 'moved' }
  /**
   * 전략·설정값·기간·금액은 보관했지만 섹터는 넘기지 못했다. 이동하지 않고 이 문장과 함께 섹터·비중 단계로 가는
   * 연결을 보인다
   */
  | { status: 'reselectSectors'; message: string }
  /** 아무것도 넘기지 못했다. 이동하지 않고 이 문장을 결과 화면에 보인다 */
  | { status: 'failed'; message: string }
  /** 화면을 떠나 요청을 끊었다. 사용자에게 보일 실패가 아니다 */
  | { status: 'canceled' }

/** 003 설정 화면이 다루는 분석 방식. 목표 위험 맞춤 같은 별도 시나리오는 그 화면의 상태에 없다 */
const MAIN_SCENARIO = 'FULL_PERIOD'

const REGISTRY_FAILED = '설정 화면에 넘길 전략 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const CATALOG_FAILED = '설정 화면에 넘길 섹터 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const STORAGE_FAILED =
  '이 브라우저에서는 조건을 설정 화면으로 넘길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'
const CONFIG_UNREADABLE =
  '이 결과의 조건을 설정 화면이 읽는 모양으로 옮길 수 없습니다. 설정 화면에서 조건을 직접 골라 주세요.'
const SCENARIO_UNSUPPORTED =
  '이 결과는 별도 시나리오로 계산한 결과라 설정 화면으로 조건을 넘길 수 없습니다. 원래 결과에서 다시 시작해 주세요.'
const KEPT_OTHER_CONDITIONS =
  '전략·설정값·분석 기간·시작 금액은 설정 화면에 채워 두었으니 섹터·비중 단계에서 섹터만 다시 골라 주세요.'
const MAPPING_DIFFERS = `이 결과의 섹터 대응표가 지금 대응표와 달라 섹터는 넘기지 않았습니다. ${KEPT_OTHER_CONDITIONS}`
const TOO_FEW_KNOWN_SECTORS = `이 결과의 섹터 가운데 지금 고를 수 있는 섹터가 ${MINIMUM_SECTOR_COUNT}개보다 적어 섹터는 넘기지 않았습니다. ${KEPT_OTHER_CONDITIONS}`
const WEIGHTS_UNREADABLE = `이 결과의 섹터 비중을 섹터·비중 단계가 읽는 단위(0.1%p)로 옮길 수 없어 섹터는 넘기지 않았습니다. ${KEPT_OTHER_CONDITIONS}`

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
 * 003이 이 선택을 이어받지 못하는 까닭. 이어받을 수 있으면 `null`이다.
 *
 * <p>002의 `readStoredSelection`이 지금 목록에 없는 섹터와 쓸 수 없는 비중을 버리고, `useSectorSelection`이 대응표
 * 버전이 다르거나 남은 섹터가 둘 미만이면 앞 단계로 되돌린다. 사용자에게 알릴 문장이 달라 까닭을 나눈다.
 */
function selectionProblem(snapshot: SectorSelectionSnapshot, catalog: SectorCatalog): string | null {
  if (snapshot.mapVersion !== catalog.mapVersion) return MAPPING_DIFFERS

  const known = new Set(catalog.sectors.map((sector) => sector.code))
  const knownCodes = snapshot.selectedCodes.filter((code) => known.has(code))
  if (knownCodes.length < MINIMUM_SECTOR_COUNT) return TOO_FEW_KNOWN_SECTORS

  const usable = knownCodes.filter((code) => isValidWeightTenths(snapshot.weightTenths[code]))
  if (usable.length < MINIMUM_SECTOR_COUNT) return WEIGHTS_UNREADABLE
  return null
}

/**
 * 003의 설정 보관 모양. 같은 탭에 보관된 값이 있으면 다른 전략의 설정값과 복원을 기다리는 안내를 그대로 두고,
 * 이 결과의 전략·기간·금액만 얹는다.
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

  // 보관값이 없을 때 003이 만드는 처음 상태다. 여기에 이 탭의 앞선 값과 결과의 조건을 얹는다.
  const fresh = reconcileStoredSettings(null, registry, config.items.length)
  const previous = readStoredStrategySettings()

  return {
    ...fresh,
    selectedStrategyId: config.strategyId,
    // 다른 전략에 맞춰 둔 값은 이 결과와 무관하다. 지우면 사용자가 넣은 값이 말없이 사라진다(원칙 VI).
    valuesByStrategy: { ...(previous?.valuesByStrategy ?? {}), [config.strategyId]: values },
    invalidValues: previous?.invalidValues ?? [],
    analysisCondition: {
      ...fresh.analysisCondition,
      // 결과에 없는 값이라 이 탭에서 고른 값을 두고, 없으면 등록표 기본값을 쓴다.
      minEvaluationYears:
        previous?.analysisCondition.minEvaluationYears ?? fresh.analysisCondition.minEvaluationYears,
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

/**
 * 결과의 조건을 보관하고 설정 화면으로 옮긴다. 섹터를 넘길 수 없으면 002 선택은 건드리지 않고 003 설정만 보관한 뒤
 * 옮기지 않는다 — 섹터가 빠진 설정 화면은 조건이 다 채워진 화면으로 읽히고, 덮어쓴 선택은 사용자가 고르던 것을
 * 지운다. 아무것도 넘길 수 없으면 아무것도 보관하지 않는다.
 *
 * <p>등록표와 섹터 목록은 사용자가 누를 때 받는다. 결과 첫 화면은 둘 다 부르지 않는다(SC-001).
 */
export async function prefillStrategySettings(
  config: ResultConfig,
  navigate: (path: string) => void,
  signal?: AbortSignal,
): Promise<PrefillOutcome> {
  if (config.scenario !== MAIN_SCENARIO) return { status: 'failed', message: SCENARIO_UNSUPPORTED }

  let registry: RegistryResponse
  let catalog: SectorCatalog
  try {
    const [loadedRegistry, catalogResult] = await Promise.all([
      fetchStrategyRegistry(config.items.length, signal),
      fetchSectorCatalog(signal),
    ])
    if (signal?.aborted) return { status: 'canceled' }
    // 목록을 읽지 못하면 대응표 버전이 같은지 판정할 수 없다. 003도 같은 목록으로 앞 단계를 확인한다.
    if (catalogResult.status !== 'loaded') return { status: 'failed', message: CATALOG_FAILED }
    registry = loadedRegistry
    catalog = catalogResult.catalog
  } catch (error) {
    if (signal?.aborted || normalizeStrategyRegistryError(error).canceled) {
      return { status: 'canceled' }
    }
    return { status: 'failed', message: REGISTRY_FAILED }
  }

  const settings = buildStrategySettings(config, registry)
  if (settings === null) return { status: 'failed', message: CONFIG_UNREADABLE }

  const selection = buildSelectionSnapshot(config, catalog)
  const problem = selectionProblem(selection, catalog)
  if (problem !== null) {
    // 대응표와 무관한 조건은 넘긴다. 섹터를 다시 고르면 설정 화면이 이 값으로 이어진다.
    if (!writeStoredStrategySettings(settings)) return { status: 'failed', message: STORAGE_FAILED }
    return { status: 'reselectSectors', message: problem }
  }

  const previousSelection = readRaw(SECTOR_SELECTION_STORAGE_KEY)
  if (!writeStoredSelection(selection)) {
    return { status: 'failed', message: STORAGE_FAILED }
  }
  if (!writeStoredStrategySettings(settings)) {
    restoreRaw(SECTOR_SELECTION_STORAGE_KEY, previousSelection)
    return { status: 'failed', message: STORAGE_FAILED }
  }

  navigate(STRATEGY_SETTINGS_PATH)
  return { status: 'moved' }
}
