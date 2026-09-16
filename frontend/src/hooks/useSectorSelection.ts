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

/**
 * 쉼표로 이어 붙인 질의 문자열을 **적힌 자리 그대로** 읽는다.
 *
 * <p>`sectors`와 `instruments`는 n번째끼리 짝을 이루므로, 어느 한쪽에서 칸을 지우면 그 뒤의 자리가
 * 모두 한 칸씩 밀려 <b>다른 섹터의 종목이 앞 섹터에 붙는다.</b> 그래서 빈 칸도 지우지 않고, 빈
 * 칸이 하나라도 있으면 자리와 값의 대응이 이미 깨진 링크로 보아 `null`을 돌려준다.
 *
 * <p>돌려주는 값의 뜻은 셋이다 — `[]`는 그 파라미터가 아예 없는 것, `null`은 적혀 있으나 온전하지
 * 않은 것, 배열은 읽어 낸 자리다.
 */
function parsePositionalList(raw: string | null): string[] | null {
  if (!raw) return []
  const tokens = raw.split(',').map((item) => item.trim())
  return tokens.every(Boolean) ? tokens : null
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

  // **같은 섹터가 두 번 든 선택은 002가 만들 수 있는 자료가 아니다.** 링크의 중복과 달리 이것은
  // 표기가 겹친 것이 아니라 저장된 자료가 깨진 것이므로, 중복만 접지 않고 전체를 버린다 —
  // 접으면 사용자가 정한 적 없는 크기의 후보 집합이 만들어지고 그 수가 `universeSize`로 서버에
  // 나간다(spec Edge Cases "손상됐거나").
  if (new Set(items.map((item) => item.sectorId)).size !== items.length) return null

  return {
    mappingVersion: typeof parsed.mappingVersion === 'string' ? parsed.mappingVersion : null,
    items,
  }
}

/**
 * 두 섹터 집합이 같은가. 순서는 보지 않는다.
 *
 * 길이 비교로 시작할 수 있는 것은 **양쪽 모두 중복이 없기 때문이다** — 링크 쪽은 호출자가
 * 중복을 접어 넘기고, 저장 자료는 중복이 있으면 `readStoredSelection`이 통째로 버린다. 그
 * 전제가 깨지면 이 비교도 함께 틀린다.
 */
function sameSectors(stored: SectorSelectionItem[], sectorIds: string[]): boolean {
  if (stored.length !== sectorIds.length) return false
  const left = new Set(stored.map((item) => item.sectorId))
  return sectorIds.every((sectorId) => left.has(sectorId))
}

/**
 * 003이 진행하려면 필요한 최소 섹터 수.
 *
 * 이 제품이 하는 일은 배분 방식이 만드는 차이를 견주는 것이고, **견주려면 나눌 곳이 둘은 있어야
 * 한다.** 섹터가 하나면 어떤 배분 전략을 골라도 그 하나에 전액이 가므로 다섯 전략이 모두 같은
 * 결과를 낸다. spec Edge Cases가 두 개 미만을 "이전 단계를 다시 완료하도록 안내"하는 상태로 둔
 * 까닭이다.
 */
const MINIMUM_SECTOR_COUNT = 2

/**
 * 질의 문자열만으로 선택을 구성한다. 링크가 온전하지 않으면 `null`이다.
 *
 * <p><b>섹터마다 종목이 짝지어져 있어야 한다.</b> `instrumentId`는 대표 ETF의 실제 식별자이고
 * (`types/sector.ts`의 `RepresentativeEtf`), 빠진 자리를 섹터 코드로 채우면 화면이 없는 값을
 * 지어내는 것이다(Constitution VI). 짝이 맞지 않는 링크는 어느 섹터의 종목인지 알 수 없으므로
 * 일부만 살리지 않고 전체를 온전하지 않은 입력으로 다룬다 — 종목 없이는 004 요청을 만들 수 없어
 * 반쪽짜리를 남겨도 쓸 곳이 없고, spec Edge Cases도 "손상됐으면"을 없는 것과 한 문장으로 묶었다.
 */
function readLinkedSelection(
  sectorTokens: string[],
  instrumentTokens: string[],
): SectorSelection | null {
  if (instrumentTokens.length !== sectorTokens.length) return null

  const items: SectorSelectionItem[] = []
  const instrumentBySector = new Map<string, string>()

  // **자리를 손대기 전에 먼저 짝을 짓는다.** 중복을 접은 뒤의 인덱스로 짝지으면 접기 전 자리와
  // 어긋나, 개수가 우연히 맞는 링크에서 다른 섹터의 종목이 앞 섹터에 붙는다.
  for (const [index, sectorId] of sectorTokens.entries()) {
    const instrumentId = instrumentTokens[index]
    const paired = instrumentBySector.get(sectorId)

    if (paired === undefined) {
      instrumentBySector.set(sectorId, instrumentId)
      items.push({ sectorId, instrumentId, targetWeight: null })
      continue
    }

    // 같은 섹터를 두 번 적은 것은 표기가 겹친 것이므로 하나로 센다. 다만 그 두 자리가 서로 다른
    // 종목을 가리키면 어느 쪽이 맞는지 알 방법이 없으므로 접지 않고 온전하지 않은 링크로 다룬다.
    if (paired !== instrumentId) return null
  }

  return { mappingVersion: null, items }
}

/**
 * 앞 단계에서 정한 섹터·비중. 아직 정해지지 않았으면 `null`이다.
 *
 * 돌려주는 `items`의 길이가 곧 등록표 조회의 `universeSize`다 — 화면이 그 수를 따로 세지 않는다.
 *
 * <p><b>"아직 마치지 않았다"에 해당하는 세 갈래를 한 자리에서 `null`로 만든다</b> — 선택이 없는
 * 경우, 저장된 자료가 손상된 경우, 고른 섹터가 두 개 미만인 경우다. spec Edge Cases가 셋을 한
 * 문장으로 묶었고, 갈래가 여러 층에 흩어지면 화면이 한쪽을 빠뜨린다(`api/sectors.ts`가 "아직 줄
 * 것이 없다"를 모두 한 층에서 갈라 놓은 것과 같은 까닭이다).
 */
export function useSectorSelection(): SectorSelection | null {
  const [params] = useSearchParams()
  const sectorsParam = params.get('sectors')
  const instrumentsParam = params.get('instruments')

  return useMemo(() => {
    const sectorTokens = parsePositionalList(sectorsParam)
    const instrumentTokens = parsePositionalList(instrumentsParam)
    if (sectorTokens === null || instrumentTokens === null) return null

    const stored = readStoredSelection()

    // 같은 섹터를 두 번 적은 것은 표기가 겹친 것일 뿐 "두 섹터를 골랐다"는 뜻이 아니므로, 저장된
    // 선택과 견줄 때는 하나로 세어 본다. 짝짓기에는 이 목록을 쓰지 않는다.
    const countedSectorIds = [...new Set(sectorTokens)]

    const selection =
      sectorTokens.length === 0
        ? stored
        : // 링크가 가리키는 섹터가 저장된 것과 같을 때만 비중을 가져온다. 저장된 선택이 종목을
          // 이미 갖고 있으므로 링크에 종목이 적혀 있지 않아도 온전하다.
          stored && sameSectors(stored.items, countedSectorIds)
          ? stored
          : readLinkedSelection(sectorTokens, instrumentTokens)

    if (selection === null || selection.items.length < MINIMUM_SECTOR_COUNT) return null
    return selection
  }, [sectorsParam, instrumentsParam])
}
