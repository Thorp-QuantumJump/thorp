import { sortSectorsForDisplay } from './sectorOrder'
import { isValidWeightTenths } from './weights'
import type { SectorSummary } from '@/types/sector'

/**
 * 선택 상태를 탭 세션 동안 보관한다(FR-014).
 *
 * 페이로드 구조와 불변식의 원본은 `contracts/selection-handoff.md` 1절이다. 이 파일은 그 계약을
 * 다시 정의하지 않고 읽고 쓰는 쪽을 만든다.
 *
 * **읽어 온 값을 신뢰하지 않는다.** `sessionStorage`는 사용자가 편집할 수 있는 자리다. 섹터
 * 코드는 현재 `GET /sectors` 응답에 있는 코드만, 비중은 0 이상 1000 이하 정수만 받아들인다.
 * 검사에서 빠진 항목은 **조용히 버리지 않고** 빠졌다는 사실을 돌려준다(원칙 VI). 무엇을 제안할지는
 * 006이 정했다(FR-008, `SectorSelectionPage`의 `missingCount` 문장) — 대체 섹터를 자동으로 고르지 않고
 * 사용자가 직접 고르게 한다.
 */

/** 014의 `thorp.auth.returnTarget`과 같은 이름 규칙이다(research.md 결정 3). */
export const SECTOR_SELECTION_STORAGE_KEY = 'thorp.diagnosis.sectorSelection'

/** 페이로드 형식의 번호다. 형식을 바꾸면 올린다. */
export const SECTOR_SELECTION_PAYLOAD_VERSION = 1

/** 저장하고 복원하는 값이다. 화면에 표시하기 위한 일시 값은 담지 않는다. */
export type SectorSelectionSnapshot = {
  /** 값을 만들 당시의 대응표 버전이다 (원칙 VIII). */
  mapVersion: string
  /** 표시 순서로 정규화되어 있다. */
  selectedCodes: string[]
  weightTenths: Record<string, number>
  weightsTouched: boolean
}

/**
 * 보관된 값을 쓸 수 없었던 까닭이다.
 *
 * - `unreadable`: 읽을 수 없거나 알 수 없는 형식이다
 * - `newerFormat`: `version`이 이 화면이 아는 값보다 크다. 배포 중 열려 있던 탭에서 생긴다
 *
 * 둘 다 빈 상태로 시작하되 **이전 입력이 남지 않았다는 사실을 한 문장으로 알린다**(spec Edge Cases).
 */
export type SelectionRestoreProblem = 'unreadable' | 'newerFormat'

/**
 * 보관된 항목을 쓰지 못한 까닭이다. **셋을 한 자루에 담지 않는다.**
 *
 * 화면이 "지금 목록에 없어 빠졌다"고만 말하면 비중이 망가졌거나 중복이라 빠진 항목까지 없는
 * 섹터로 둔갑한다. 무엇을 제안할지는 006이 정했지만(직접 고르게 한다), 무엇이 왜 빠졌는지 사실을
 * 정확히 알리는 것은 002의 몫이다(원칙 VI).
 */
export type SelectionDropReason =
  /** 현재 `GET /sectors` 응답에 없는 코드다. 006의 대체 섹터 안내 문장이 붙는 경우가 이것이다. */
  | 'notInCatalog'
  /** 코드는 있으나 비중이 0 이상 1000 이하 정수가 아니다. */
  | 'unusableWeight'
  /** 같은 코드가 여러 번 실려 있었다. */
  | 'duplicate'

export type DroppedSelection = {
  code: string
  reason: SelectionDropReason
}

export type SelectionRestoreResult = {
  /** 쓸 수 있는 값이 없으면 null이다. */
  snapshot: SectorSelectionSnapshot | null
  /**
   * 보관되어 있었으나 **끝내 선택에 남지 못한** 항목이다. 코드마다 한 번만 담는다.
   *
   * 같은 코드가 두 번 실려 있고 그 가운데 하나가 받아들여졌다면 그 코드는 잃은 것이 아니므로
   * 여기 담기지 않는다. 담으면 사용자가 실제로 잃은 섹터 수보다 큰 숫자를 보게 된다.
   */
  dropped: DroppedSelection[]
  problem: SelectionRestoreProblem | null
}

const EMPTY_RESULT: SelectionRestoreResult = { snapshot: null, dropped: [], problem: null }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function unreadable(): SelectionRestoreResult {
  return { snapshot: null, dropped: [], problem: 'unreadable' }
}

/**
 * 보관된 값을 읽어 현재 섹터 목록에 비추어 검증한다.
 *
 * 저장소 접근이 막힌 환경에서는 예외를 삼키고 빈 결과를 돌려준다. 화면을 오류로 막지 않는다
 * (spec Edge Cases). 그 경우는 사용자가 넣은 값을 잃은 것이 아니므로 알림도 붙이지 않는다.
 */
export function readStoredSelection(
  sectors: readonly SectorSummary[],
): SelectionRestoreResult {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
  } catch {
    return EMPTY_RESULT
  }

  if (raw === null) return EMPTY_RESULT

  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    return unreadable()
  }

  if (!isRecord(payload)) return unreadable()

  if (typeof payload.version !== 'number' || !Number.isInteger(payload.version)) {
    return unreadable()
  }
  if (payload.version > SECTOR_SELECTION_PAYLOAD_VERSION) {
    // 읽는 쪽이 아는 값보다 큰 형식은 해석하지 않는다. 억지로 읽으면 뜻이 달라진 필드를 그대로 쓴다.
    //
    // **읽기를 거부할 뿐 보관을 영구히 막지는 않는다.** 화면이 열리는 것만으로 남의 값이 지워지는
    // 일은 쓰는 쪽에서 막지만(`SectorSelectionProvider`가 첫 렌더에서는 쓰지 않는다), 그 뒤
    // 사용자가 이 탭에서 섹터를 고르거나 비중을 넣으면 그 값을 보관한다. 새 형식을 지키겠다고
    // 쓰기를 아주 막으면 **사용자가 방금 넣은 입력이 새로고침에 조용히 사라진다.** 지키려는 것은
    // 남의 값이지 사용자의 명시적인 행동이 아니다.
    return { snapshot: null, dropped: [], problem: 'newerFormat' }
  }
  if (payload.version < SECTOR_SELECTION_PAYLOAD_VERSION) return unreadable()

  if (typeof payload.mapVersion !== 'string') return unreadable()
  if (typeof payload.weightsTouched !== 'boolean') return unreadable()
  if (!Array.isArray(payload.selected)) return unreadable()

  // 표시 순서는 화면이 정한다. 보관된 배열 순서에 기대지 않는다(research.md 결정 2).
  const displayRank = new Map(
    sortSectorsForDisplay(sectors).map((sector, index) => [sector.code, index] as const),
  )

  const selectedCodes: string[] = []
  const weightTenths: Record<string, number> = {}
  const candidates: DroppedSelection[] = []

  for (const item of payload.selected) {
    // 구조 자체가 깨진 페이로드는 "섹터가 사라진 것"과 다르다. 무엇이 빠졌는지 말할 수도 없으므로
    // 항목 하나를 버리는 대신 페이로드 전체를 쓰지 않는다.
    if (!isRecord(item) || typeof item.code !== 'string') return unreadable()

    const { code } = item
    const weight = item.weightTenths

    // 까닭을 나눠 담는다. 화면이 세 경우를 같은 문장으로 알리면 사실과 다른 말을 하게 된다.
    if (!displayRank.has(code)) {
      candidates.push({ code, reason: 'notInCatalog' })
      continue
    }
    if (!isValidWeightTenths(weight)) {
      candidates.push({ code, reason: 'unusableWeight' })
      continue
    }
    if (code in weightTenths) {
      // 같은 코드가 두 번 실려 오면 뒤엣것을 버린다. 불변식 1이 중복을 금지한다.
      candidates.push({ code, reason: 'duplicate' })
      continue
    }

    selectedCodes.push(code)
    weightTenths[code] = weight
  }

  selectedCodes.sort((left, right) => (displayRank.get(left) ?? 0) - (displayRank.get(right) ?? 0))

  // 끝내 선택에 남은 코드는 잃은 것이 아니다. 같은 코드를 여러 번 담지도 않는다. 이 두 가지를
  // 하지 않으면 화면이 실제로 잃은 섹터 수보다 큰 숫자를 말하게 된다.
  const dropped: DroppedSelection[] = []
  const reported = new Set<string>()
  for (const candidate of candidates) {
    if (candidate.code in weightTenths) continue
    if (reported.has(candidate.code)) continue
    reported.add(candidate.code)
    dropped.push(candidate)
  }

  return {
    snapshot: {
      // 값을 다시 만드는 것이 아니라 읽어 온 것이므로 만들 당시의 대응표 버전을 그대로 둔다.
      mapVersion: payload.mapVersion,
      selectedCodes,
      weightTenths,
      weightsTouched: payload.weightsTouched,
    },
    dropped,
    problem: null,
  }
}

/**
 * 선택 상태를 보관한다. 보관에 성공했는지를 돌려준다.
 *
 * **값을 손대지 않는다.** 합계를 저장 시점에 1000으로 맞추는 조정, 표시 정밀도를 넘어선 반올림,
 * 비중이 0인 섹터 제외를 모두 하지 않는다. 사용자가 입력을 마치지 못한 채 화면을 떠났다가
 * 돌아오면 그 상태 그대로 보여야 하며, 저장하면서 맞추면 사용자 입력을 몰래 바꾸는 것이 된다
 * (계약 불변식 3, FR-013, 원칙 VI). 003이 읽는 값도 이 함수가 쓴 값 하나이므로, 여기서 값을
 * 건드리지 않는 것이 곧 SC-013이 요구하는 "마지막 입력과 다르지 않음"이다.
 *
 * **돌려주는 값이 필요한 까닭**: 저장소가 막힌 환경에서는 예외를 삼키고 메모리 상태로만
 * 동작한다(계약 「읽는 쪽의 의무」). 그 상태로도 이 화면은 정상 동작하지만, 003으로 넘기는
 * 수단은 이 저장소 항목 하나뿐이라 보관이 실패하면 **인계할 방법이 없다.** 넘기는 쪽이 그
 * 사실을 알 수 있어야 사용자를 빈 다음 화면으로 보내지 않는다(원칙 VI).
 */
export function writeStoredSelection(snapshot: SectorSelectionSnapshot): boolean {
  const payload = {
    version: SECTOR_SELECTION_PAYLOAD_VERSION,
    mapVersion: snapshot.mapVersion,
    selected: snapshot.selectedCodes.map((code) => ({
      code,
      weightTenths: snapshot.weightTenths[code] ?? 0,
    })),
    weightsTouched: snapshot.weightsTouched,
  }

  try {
    window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, JSON.stringify(payload))
    return true
  } catch {
    // 저장소가 막힌 환경에서는 메모리 상태로만 동작한다. 화면을 오류로 막지 않는다.
    return false
  }
}

/** 보관된 값을 지운다. 저장소가 막혀 있어도 화면이 죽지 않는다. */
export function clearStoredSelection(): void {
  try {
    window.sessionStorage.removeItem(SECTOR_SELECTION_STORAGE_KEY)
  } catch {
    // 위와 같다.
  }
}
