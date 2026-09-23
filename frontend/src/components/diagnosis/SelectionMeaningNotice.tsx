/**
 * 고른 투자 대상이 무엇을 뜻하는지 선택 요약 옆에서 알린다(FR-009).
 *
 * 하나라도 고른 뒤에는 **언제나** 보인다. 이 화면의 '선택'이 두 가지 뜻을 동시에 갖기
 * 때문이다. 자동 배분 전략에서는 투자 후보 집합이고, '고정 비중' 전략에서는 다음 단계에서
 * 정할 비중이 붙는 대상이다. 구분해 두지 않으면 사용자는 고른 대상을 매번 모두 보유한다는
 * 뜻으로 읽는다.
 *
 * **비중을 여기서 말하지 않는다.** 비중을 정하는 자리가 다음 단계로 옮겨 갔으므로, 0%인
 * 대상이 무엇을 뜻하는지(FR-016)도 비중을 실제로 넣는 그 화면이 알린다. 없는 입력 칸을 두고
 * 그 값의 뜻을 설명하면 사용자는 이 화면에서 그것을 찾게 된다.
 */

type SelectionMeaningNoticeProps = {
  /** 지금 고른 투자 대상 개수다. 0이면 이 안내 자체가 나타나지 않는다. */
  selectedCount: number
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

export function SelectionMeaningNotice({
  selectedCount,
  className,
}: SelectionMeaningNoticeProps) {
  if (selectedCount === 0) return null

  return (
    <div className={className} data-slot="selection-meaning">
      <p data-slot="candidate-meaning">
        고른 투자 대상은 자동 배분 전략이 고를 수 있는 후보 집합입니다. 매번 모두 보유한다는 뜻이
        아니며, '고정 비중' 전략에 쓰이는 비중은 다음 단계에서 정합니다.
      </p>
    </div>
  )
}
