import type { ListingWindow } from '@/diagnosis/listingWindow'

/**
 * 상장일 차이로 공통 비교 기간이 줄어든다는 사실을 선택 단계에서 한 문장으로 알린다(FR-008).
 *
 * 선택 섹터가 둘 이상이고 상장일이 서로 다를 때만 나타난다(research.md 결정 7). 판정은 이
 * 컴포넌트가 하지 않고 `describeListingWindow`가 돌려준 상태를 그대로 따른다. 판정이 두 곳에
 * 있으면 한쪽만 고쳐져 화면과 규칙이 갈라진다.
 *
 * 문장이 담는 것은 셋이다.
 *
 * 1. 언제부터 비교할 수 있는가
 * 2. 어느 섹터의 상장일 때문인가
 * 3. **이 값은 상장일만 반영한 것이며 전략 워밍업 몫은 003이 알린다는 사실**
 *
 * 세 번째가 빠지면 사용자는 이 날짜를 최종 비교 시작일로 읽는다. 실제로는 전략을 고른 뒤 워밍업
 * 때문에 더 늦어질 수 있고, 그 사실을 결과 화면에서 처음 알게 되는 것이 SC-003이 막으려는
 * 상황이다.
 *
 * **접어 두고 '자세히 보기'로 펼치지 않는다.** 원칙 II가 결과 의미를 바꾸는 자료 부족 표시를
 * 상세 안에 숨기지 못하게 한다.
 *
 * **데이터 입력이나 자료 공급원 선택을 요구하지 않는다**(FR-008, SC-005). 이 컴포넌트에는 입력
 * 요소도 사용자가 고를 항목도 없다.
 *
 * 대표 ETF가 없는 섹터는 001 계약의 불변식에 따라 목록에 실리지 않으므로 그 경우의 안내를 두지
 * 않는다(spec Clarifications).
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와 나타나는 조건까지만 만들었고 문구는
 * UI 결정 항목이 정해질 때 채운다.
 */

type ListingWindowNoticeProps = {
  listingWindow: ListingWindow
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

export function ListingWindowNotice({ listingWindow, className }: ListingWindowNoticeProps) {
  if (listingWindow.status === 'silent') return null

  const { bindingSectorName, commonStartFromListing } = listingWindow

  return (
    <p className={className} role="status" data-slot="listing-window-notice">
      {listingWindow.status === 'unavailable' ? (
        <>
          {bindingSectorName}의 대표 ETF가 {commonStartFromListing}에 상장해 아직 함께 비교할 수
          있는 기간이 없습니다.
        </>
      ) : (
        <>
          {bindingSectorName}의 대표 ETF 상장일이 가장 늦어 {commonStartFromListing}부터 함께
          비교할 수 있습니다. 상장일만 반영한 날짜이며, 전략을 고르면 그 전략이 필요로 하는 준비
          기간만큼 더 늦어질 수 있습니다.
        </>
      )}
    </p>
  )
}
