import { TOTAL_WEIGHT_TENTHS, formatWeightTenths } from '@/diagnosis/weights'

/**
 * 비중 합계와 남은 비중을 보이고 '동일하게 나누기'를 둔다.
 *
 * 합계가 100%가 아니면 **그 위치에** 남은 비중을 부족·초과로 표시한다. 별도 알림 영역으로 빼지
 * 않는다(FR-007, 원칙 VI, research.md 결정 7).
 *
 * **비중을 자동으로 정규화하거나 조정하지 않는다**(SC-002). 이 컴포넌트가 하는 일은 사용자가
 * 넣은 값을 더해 보이는 것과, 사용자가 스스로 누를 수 있는 되돌리기 하나를 두는 것뿐이다.
 *
 * 합계가 바뀌는 사실이 보조 기술에도 전달되도록 합계 문장을 읽어 주는 영역에 둔다
 * (checklists/ux.md CHK034).
 */

type WeightTotalProps = {
  totalTenths: number
  /** 남은 비중이다. 음수면 초과다. */
  remainderTenths: number
  isBalanced: boolean
  /** '동일하게 나누기'다. 값을 다시 채우면서 사용자 수정 여부를 되돌린다. */
  onDistributeEven: () => void
  className?: string
}

export function WeightTotal({
  totalTenths,
  remainderTenths,
  isBalanced,
  onDistributeEven,
  className,
}: WeightTotalProps) {
  /*
    100% 가운데 얼마가 찼고 얼마가 넘쳤는지를 눈으로 보인다. 백분율로 바꾸는 것은 막대 너비를
    정하는 표시 목적뿐이고, 판정에 쓰는 값은 위의 정수 그대로다.

    합계가 100%를 넘으면 막대의 기준 길이를 합계로 늘린다. 100%에 고정하면 넘친 몫이 막대 밖으로
    나가 보이지 않아, 얼마나 넘쳤는지를 보이려던 목적이 사라진다.
  */
  const scaleTenths = Math.max(totalTenths, TOTAL_WEIGHT_TENTHS)
  const filledWidth = (Math.min(Math.max(totalTenths, 0), TOTAL_WEIGHT_TENTHS) / scaleTenths) * 100
  const overflowWidth = (Math.max(totalTenths - TOTAL_WEIGHT_TENTHS, 0) / scaleTenths) * 100
  const state = isBalanced ? 'balanced' : remainderTenths > 0 ? 'short' : 'over'

  return (
    <div className={className} data-slot="weight-total">
      <p role="status" aria-live="polite" data-slot="weight-total-message">
        <span data-slot="weight-total-sum">합계 {formatWeightTenths(totalTenths)}%</span>
        {isBalanced ? null : (
          <span data-slot="weight-total-remainder">
            {remainderTenths > 0
              ? ` · ${formatWeightTenths(remainderTenths)}%p 부족합니다.`
              : ` · ${formatWeightTenths(-remainderTenths)}%p 초과했습니다.`}
          </span>
        )}
      </p>

      {/*
        같은 사실을 눈으로도 보인다. 문장이 이미 그 값을 읽어 주므로 이 막대는 보조 기술에
        두 번 읽히지 않도록 숨긴다. 명세는 "남은 비중을 그 위치에 표시한다"까지만 요구하고
        방식을 정하지 않았다.
      */}
      <span data-slot="weight-total-bar" data-state={state} aria-hidden="true">
        <span data-slot="weight-total-fill" style={{ width: `${filledWidth}%` }} />
        {overflowWidth > 0 ? (
          <span data-slot="weight-total-over" style={{ width: `${overflowWidth}%` }} />
        ) : null}
      </span>

      {/*
        FR-007은 합계가 맞지 않을 때 이 되돌리기를 요구한다. 합계가 맞은 뒤에도 자리를 지키는
        까닭은 사용자가 값을 고치는 동안 버튼이 나타났다 사라지면 초점이 흔들리기 때문이다.
        누르기 전에는 아무 값도 바뀌지 않으므로 SC-002와 어긋나지 않는다.
      */}
      <button type="button" onClick={onDistributeEven} data-slot="weight-total-action">
        동일하게 나누기
      </button>
    </div>
  )
}
