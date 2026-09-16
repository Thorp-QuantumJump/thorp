import { useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * 002가 확정한 섹터·비중을 읽는 **유일한 통로**다.
 *
 * <p><b>002가 구현되면 이 파일만 바꾼다.</b> 003의 다른 어떤 파일도 섹터 선택이 어디에 어떤
 * 모양으로 저장되는지 알지 않는다 — 알게 두면 002가 자기 저장 방식을 정할 때 003의 여러 파일이
 * 함께 흔들린다. 그래서 읽는 자리를 여기 하나로 좁혀 두었다.
 *
 * <p>002의 선택 화면(체크 상자와 비중 입력)이 아직 없으므로 지금은 두 곳에서 읽는 어댑터다.
 *
 * <ol>
 *   <li><code>sessionStorage</code>의 <code>thorp.sectorSelection</code> — 002가 확정한 완전한
 *       자료다. 비중까지 담는다.</li>
 *   <li>질의 문자열 <code>?sectors=&amp;instruments=</code> — 링크로 바로 열었을 때의 대역이다.
 *       007 <code>ParamSearchPage</code>가 대상을 질의 문자열에 두는 것과 같은 방식이며, 비중은
 *       담기지 않으므로 <code>targetWeight</code>가 <code>null</code>이다.</li>
 * </ol>
 *
 * <p><b>질의 문자열이 이번 진입의 대상이다.</b> 질의 문자열이 가리키는 섹터 집합이 저장된 것과
 * 다르면 저장된 비중을 쓰지 않는다 — 링크로 다른 섹터를 열었는데 앞선 세션의 비중을 그대로
 * 얹으면 화면이 사용자가 정한 적 없는 비중을 보이게 된다. 집합이 같을 때만 비중을 가져온다.
 *
 * <p>어느 쪽에도 없으면 <code>null</code>이고, 화면은 "섹터·비중 단계를 먼저 마쳐 주세요"를
 * 안내한다. 임의의 섹터를 골라 대신 채우지 않는다.
 */

/** 002가 저장하는 자리. 이 이름을 아는 파일은 여기뿐이다. */
export const SECTOR_SELECTION_STORAGE_KEY = 'thorp.sectorSelection'

/** 고른 섹터 하나와 그 대표 상품, 그리고 사용자가 정한 비중. */
export type SectorSelectionItem = {
  sectorId: string
  instrumentId: string
  /** 002가 확정하기 전에는 `null`이다. 0과 다르다 — "정해지지 않았다"는 뜻이다. */
  targetWeight: number | null
}

/** 002가 확정한 섹터 선택 한 벌. */
export type SectorSelection = {
  /** 002가 붙이는 대응표 버전. 대역 경로로 읽었으면 `null`이다. */
  mappingVersion: string | null
  items: SectorSelectionItem[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 쉼표로 이어 붙인 질의 문자열을 목록으로. 빈 값과 중복은 버린다. */
function parseList(raw: string | null): string[] {
  if (!raw) return []
  return [...new Set(raw.split(',').map((item) => item.trim()).filter(Boolean))]
}

/** 저장된 자료 하나를 읽는다. 모양이 다르면 `null`이며, 한 항목이라도 어긋나면 전체를 버린다. */
function readItem(value: unknown): SectorSelectionItem | null {
  if (!isRecord(value)) return null
  if (typeof value.sectorId !== 'string' || typeof value.instrumentId !== 'string') return null

  const weight = value.targetWeight
  if (weight !== undefined && weight !== null && typeof weight !== 'number') return null

  return {
    sectorId: value.sectorId,
    instrumentId: value.instrumentId,
    targetWeight: typeof weight === 'number' ? weight : null,
  }
}

/**
 * `sessionStorage`에 저장된 선택을 읽는다.
 *
 * 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서 접근 자체가 예외를 던지므로 감싼다. 읽지
 * 못하는 것은 선택이 없는 것과 같게 다룬다.
 */
function readStoredSelection(): SectorSelection | null {
  let raw: string | null = null
  try {
    raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
  } catch {
    return null
  }
  if (!raw) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }

  if (!isRecord(parsed) || !Array.isArray(parsed.items)) return null

  const items: SectorSelectionItem[] = []
  for (const candidate of parsed.items) {
    const item = readItem(candidate)
    if (!item) return null
    items.push(item)
  }
  if (items.length === 0) return null

  return {
    mappingVersion: typeof parsed.mappingVersion === 'string' ? parsed.mappingVersion : null,
    items,
  }
}

/** 두 섹터 집합이 같은가. 순서는 보지 않는다. */
function sameSectors(stored: SectorSelectionItem[], sectorIds: string[]): boolean {
  if (stored.length !== sectorIds.length) return false
  const left = new Set(stored.map((item) => item.sectorId))
  return sectorIds.every((sectorId) => left.has(sectorId))
}

/**
 * 앞 단계에서 정한 섹터·비중. 아직 정해지지 않았으면 `null`이다.
 *
 * 돌려주는 `items`의 길이가 곧 등록표 조회의 `universeSize`다 — 화면이 그 수를 따로 세지 않는다.
 */
export function useSectorSelection(): SectorSelection | null {
  const [params] = useSearchParams()
  const sectorsParam = params.get('sectors')
  const instrumentsParam = params.get('instruments')

  return useMemo(() => {
    const sectorIds = parseList(sectorsParam)
    const instrumentIds = parseList(instrumentsParam)
    const stored = readStoredSelection()

    if (sectorIds.length === 0) {
      return stored
    }

    // 링크가 가리키는 섹터가 저장된 것과 같을 때만 비중을 가져온다.
    if (stored && sameSectors(stored.items, sectorIds)) {
      return stored
    }

    // 대역 경로다. 비중은 담기지 않으므로 `null`로 둔다.
    return {
      mappingVersion: null,
      items: sectorIds.map((sectorId, index) => ({
        sectorId,
        instrumentId: instrumentIds[index] ?? sectorId,
        targetWeight: null,
      })),
    }
  }, [sectorsParam, instrumentsParam])
}
