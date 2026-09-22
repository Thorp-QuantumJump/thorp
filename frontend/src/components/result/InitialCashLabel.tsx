import { useChartStable } from '@/components/charts/chart-context'
import { formatAxisAmount } from '@/components/result/formatResult'

/**
 * 시작 금액 기준선에 붙는 이름표(FR-001).
 *
 * <p>기준선 자체는 `Grid`의 `highlightRowValues`가 긋는다. 선만 있으면 그것이 무슨 선인지 그래프
 * 안에서는 알 수 없어, 곡선이 원금 위에 있는지 아래에 있는지를 눈으로 바로 읽지 못한다. 그래서 선
 * 옆에 금액을 직접 적는다.
 *
 * <p>글자 뒤에 바탕색 외곽선을 둘러 곡선이나 격자와 겹쳐도 읽히게 한다. 폭을 재서 배경 사각형을
 * 깔지 않는 이유는 글꼴에 따라 폭이 달라져 사각형만 남거나 글자가 삐져나오기 때문이다.
 */
export type InitialCashLabelProps = {
  /** 기준선이 놓일 세로축 좌표값. 로그 눈금에서는 금액의 상용로그다 */
  at: number
  /** 이름표에 적을 원화 금액 */
  amount: number
}

export function InitialCashLabel({ at, amount }: InitialCashLabelProps) {
  const { yScale, innerHeight } = useChartStable()

  const y = yScale(at)
  if (y == null || !Number.isFinite(y)) return null

  // 기준선이 그림 위쪽에 가까우면 이름표가 잘린다. 그때만 선 아래로 내린다.
  const above = y - 7
  const baseline = above < 12 ? Math.min(y + 15, innerHeight - 2) : above

  return (
    <text
      data-testid="initial-cash-label"
      dominantBaseline="middle"
      fill="var(--thorp-copy)"
      fontSize={11}
      paintOrder="stroke"
      stroke="var(--thorp-surface)"
      strokeLinejoin="round"
      strokeWidth={3.5}
      x={4}
      y={baseline}
    >
      {'시작 금액 ' + formatAxisAmount(amount)}
    </text>
  )
}

export default InitialCashLabel
