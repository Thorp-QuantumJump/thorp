/**
 * 고른 섹터와 입력한 비중이 각각 무엇을 뜻하는지 비중 입력 묶음의 꼬리에 알린다.
 *
 * 두 문장이 서로 다른 조건에서 나타난다(research.md 결정 7).
 *
 * | 문장 | 나타나는 조건 | 근거 |
 * |---|---|---|
 * | 선택 섹터는 자동 전략의 후보 집합이고, 입력한 비중은 '내가 정한 비중'에 쓰인다 | 섹터를 하나 이상 고른 뒤 **항상** | FR-009 |
 * | 0%인 섹터는 '내가 정한 비중'에 담기지 않지만 후보 집합에는 남는다 | 비중이 0인 섹터가 하나 이상일 때만 | FR-016 |
 *
 * 앞 문장이 상시 표시인 까닭은 이 화면의 '선택'이 두 가지 뜻을 동시에 갖기 때문이다. 자동 배분
 * 전략에서는 투자 후보 집합이고, '내가 정한 비중' 전략에서는 사용자가 넣은 값 그대로다. 둘을
 * 구분해 두지 않으면 사용자는 고른 섹터를 매번 모두 보유한다는 뜻으로 읽는다.
 *
 * 뒷 문장은 0%를 넣은 사용자가 "그 섹터가 빠졌다"고 오해하는 것을 막는다. **0%인 섹터를 선택
 * 목록에서 빼지 않는다**(SC-010). 003이 선택 섹터 개수로 유효성 제약을 계산하므로 빼면 003의
 * 계산과 어긋난다. 그 사실은 이 컴포넌트가 아니라 선택 상태가 지키며, 여기서는 지켜진 사실을
 * 말로 옮길 뿐이다.
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와 나타나는 조건까지만 만들었고 문구는
 * UI 결정 항목이 정해질 때 채운다.
 */

type SelectionMeaningNoticeProps = {
  /** 지금 고른 섹터 개수다. 0이면 이 안내 자체가 나타나지 않는다. */
  selectedCount: number
  /** 비중이 0인 섹터가 하나 이상인가. */
  hasZeroWeight: boolean
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

export function SelectionMeaningNotice({
  selectedCount,
  hasZeroWeight,
  className,
}: SelectionMeaningNoticeProps) {
  if (selectedCount === 0) return null

  return (
    <div className={className} data-slot="selection-meaning">
      <p data-slot="candidate-meaning">
        고른 섹터는 자동 배분 전략이 고를 수 있는 투자 후보 집합입니다. 매번 모두 보유한다는 뜻이
        아니며, 여기에 입력한 비중은 '내가 정한 비중' 전략에만 쓰여 자동 전략이 계산한 비중과
        구분됩니다.
      </p>

      {hasZeroWeight ? (
        <p data-slot="zero-weight-meaning">
          비중이 0%인 섹터는 '내가 정한 비중'에는 담기지 않지만, 자동 배분 전략의 후보 집합에는
          그대로 남습니다.
        </p>
      ) : null}
    </div>
  )
}
