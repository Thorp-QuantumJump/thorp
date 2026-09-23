import type { SectorSummary } from '@/types/sector'

/**
 * 대표 ETF 상장일만으로 공통 비교 시작일을 구한다(research.md 결정 4).
 *
 * ```
 * commonStartFromListing = max(선택 섹터의 representativeEtf.listedOn)
 * bindingSectorCode      = 그 최댓값을 만든 섹터
 * ```
 *
 * **`GET /sectors/evaluation-window`를 부르지 않는다.** 그 산식은 003이 소유하는 워밍업 거래일
 * 수를 필수 입력으로 받아 전략을 고르기 전에는 채울 수 없다(contracts/README.md). 재료는 이미
 * 화면에 있다. `GET /sectors`가 섹터마다 `representativeEtf.listedOn`을 주므로 추가 호출이
 * 필요 없다.
 *
 * 여기서 구하는 값은 001의 산식에서 `warmupTradingDays = 0`인 특수한 경우와 정확히 같다. 002가
 * 다른 규칙을 새로 만드는 것이 아니다.
 *
 * **거래일 달력을 다루지 않는다.** 워밍업이 0이면 거래일 수를 세는 부분이 사라지고 날짜 최댓값만
 * 남는다. 그래서 이 파일에는 기간을 세는 계산이 없고, 화면이 음수 기간으로 이상한 문장을 만들
 * 여지도 없다.
 */

/** `YYYY-MM-DD` 한 가지만 받는다. 001 계약이 상장일을 이 형식으로 준다. */
const ISO_DATE_SHAPE = /^\d{4}-\d{2}-\d{2}$/

/**
 * 선택 조합에서 상장일이 만들어 내는 상태다.
 *
 * - `silent`: 안내할 사실이 없다. 선택이 두 개 미만이거나 상장일이 모두 같은 경우다
 * - `narrowed`: 상장일이 서로 달라 공통 비교 기간이 가장 늦은 상장일 이후로 줄어든다
 * - `unavailable`: 가장 늦은 상장일이 오늘 이후여서 공통으로 비교할 기간이 남지 않는다
 *
 * **`unavailable` 판정은 근사치다.** 정확한 판정에는 자료 마감일이 필요한데 `GET /sectors`
 * 응답에 `dataDeadline`이 없고 상장폐지일은 아예 없다. 정확한 판정은 003이 `evaluable: false`로
 * 받는다(research.md 결정 4의 경계 동작). 화면은 이 상태에서 "언제부터 비교할 수 있다"는 문장을
 * 만들지 않는 데까지만 한다.
 */
export type ListingWindow =
  | { status: 'silent' }
  | {
      status: 'narrowed' | 'unavailable'
      /** `YYYY-MM-DD`. 선택 섹터 가운데 가장 늦은 대표 ETF 상장일이다. */
      commonStartFromListing: string
      /** 그 최댓값을 만든 섹터의 코드다. */
      bindingSectorCode: string
      /** 같은 섹터의 이름이다. 안내 문장이 코드가 아니라 사람이 읽는 이름을 쓴다. */
      bindingSectorName: string
    }

/** 브라우저 기준 오늘을 `YYYY-MM-DD`로 만든다. UTC로 옮기면 하루가 어긋난다. */
export function localToday(now: Date = new Date()): string {
  const year = now.getFullYear()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * 선택한 섹터들의 상장일을 견주어 안내할 사실이 있는지 가른다.
 *
 * `selectedSectors`는 **선택된 섹터만** 담는다. 목록 전체를 넘기면 고르지 않은 섹터의 상장일까지
 * 최댓값 후보가 되어 사실과 다른 날짜를 말하게 된다.
 *
 * `today`를 인자로 받는 까닭은 검사가 시계에 매이지 않게 하기 위해서다. 넘기지 않으면 브라우저
 * 기준 오늘을 쓴다.
 *
 * **읽을 수 없는 상장일이 하나라도 있으면 `silent`를 돌려준다.** 001 계약이 `YYYY-MM-DD`를
 * 보장하므로 여기 걸리는 응답은 계약 위반이고, 그 값으로 날짜를 지어내 문장을 만드는 것은 아무
 * 안내도 하지 않는 것보다 나쁘다(원칙 VI).
 */
export function describeListingWindow(
  selectedSectors: readonly SectorSummary[],
  today: string = localToday(),
): ListingWindow {
  // 비교할 상대가 없으면 "기간이 줄어든다"는 사실 자체가 없다.
  if (selectedSectors.length < 2) return { status: 'silent' }

  let binding = selectedSectors[0]
  for (const sector of selectedSectors) {
    const listedOn = sector.representativeEtf.listedOn
    if (!ISO_DATE_SHAPE.test(listedOn)) return { status: 'silent' }
    // 자리를 채운 ISO 날짜는 문자열 비교가 곧 날짜 비교다. `Date`로 옮기면 시간대가 끼어든다.
    if (listedOn > binding.representativeEtf.listedOn) binding = sector
  }

  const commonStartFromListing = binding.representativeEtf.listedOn

  // 모두 같은 날 상장했으면 어느 섹터 때문에 기간이 줄었다고 말할 수 없다.
  const allSame = selectedSectors.every(
    (sector) => sector.representativeEtf.listedOn === commonStartFromListing,
  )
  if (allSame) return { status: 'silent' }

  return {
    status: commonStartFromListing > today ? 'unavailable' : 'narrowed',
    commonStartFromListing,
    bindingSectorCode: binding.code,
    bindingSectorName: binding.name,
  }
}

/**
 * 선택 코드 목록으로 선택된 섹터만 골라낸다.
 *
 * `selectedCodes`가 이미 표시 순서로 정규화되어 있으므로 그 순서를 그대로 지킨다. 목록에 없는
 * 코드는 조용히 건너뛴다 — 상태에 들어올 때 이미 걸러지므로 여기 걸리는 코드는 없다.
 */
export function pickSelectedSectors(
  sectors: readonly SectorSummary[],
  selectedCodes: readonly string[],
): SectorSummary[] {
  const byCode = new Map(sectors.map((sector) => [sector.code, sector] as const))
  const picked: SectorSummary[] = []
  for (const code of selectedCodes) {
    const sector = byCode.get(code)
    if (sector) picked.push(sector)
  }
  return picked
}
