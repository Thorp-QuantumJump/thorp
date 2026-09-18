/**
 * 시장 흐름에서 고른 섹터를 탭 세션 동안 보관한다(FR-016).
 *
 * FR-016은 "새로고침·다른 메뉴 왕복에 남고 탭을 닫으면 풀린다"와 "유지에 로그인을 요구하지
 * 않는다"를 함께 요구한다. `sessionStorage`의 수명이 정확히 그 범위다([research.md](../../../specs/009-market-flow-overview/research.md) R5).
 * `localStorage`는 탭을 닫아도 남아 FR-016을 어기고, 서버 세션은 로그인 의존을 만들어 D02를 어긴다.
 *
 * **이 모듈은 분야·지표·기준월을 모르고, 알 필요도 없다.** 섹터 선택은 연결할 전략·대표 상품·
 * 뉴스의 범위만 좁히고 경제환경 표시는 바꾸지 않는다(FR-016, 원칙 IV). 경계를 이렇게 그어야
 * SC-004가 테스트가 아니라 구조로 지켜진다.
 *
 * **읽어 온 값을 신뢰하지 않는다.** `sessionStorage`는 사용자가 편집할 수 있는 자리라
 * 문자열이 아니거나 비어 있으면 없는 것으로 다룬다. 다만 **현재 섹터 목록에 있는 코드인지까지는
 * 여기서 보지 않는다.** 목록은 서버에서 오고 이 모듈은 화면 없이 도는 순수 함수이기 때문이다.
 * 그 대조는 목록을 쥔 쪽(T025)이 한다.
 */

/** 014의 `thorp.auth.returnTarget`, 002의 `thorp.diagnosis.sectorSelection`과 같은 이름 규칙이다. */
export const MARKET_FLOW_SECTOR_KEY = 'thorp.marketflow.sectorLink'

/**
 * 값 하나짜리라 002처럼 형식 번호를 두지 않는다.
 *
 * 002는 코드 여러 개와 비중을 함께 담아 형식이 바뀔 여지가 있지만, 여기 담기는 것은 섹터 코드
 * 문자열 하나뿐이다. 담을 것이 늘어나면 그때 객체와 번호를 들인다.
 */
function normalizeSectorCode(value: unknown): string | null {
  if (typeof value !== 'string') return null

  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

/**
 * 고른 섹터를 이 탭에 저장한다. `null`을 주면 선택을 지운다.
 *
 * 저장소를 쓸 수 없어도 **예외를 밖으로 던지지 않는다.** 시크릿 창이나 저장소를 막은 환경에서
 * 선택이 안 남는 것은 불편이지만, 화면이 죽는 것은 고장이다(002 Edge Case와 같은 판단).
 */
export function saveSelectedSector(code: unknown): void {
  const normalized = normalizeSectorCode(code)

  try {
    if (normalized === null) {
      window.sessionStorage.removeItem(MARKET_FLOW_SECTOR_KEY)
      return
    }
    window.sessionStorage.setItem(MARKET_FLOW_SECTOR_KEY, normalized)
  } catch {
    // 저장소가 막힌 환경이다. 선택은 이번 화면 안에서만 살아 있게 둔다.
  }
}

/** 이 탭에 남아 있는 선택을 읽는다. 없거나 읽을 수 없으면 `null`이다. */
export function readSelectedSector(): string | null {
  try {
    return normalizeSectorCode(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY))
  } catch {
    return null
  }
}

/** 선택을 지운다. `saveSelectedSector(null)`과 같지만 부르는 쪽의 뜻이 드러난다. */
export function clearSelectedSector(): void {
  saveSelectedSector(null)
}
