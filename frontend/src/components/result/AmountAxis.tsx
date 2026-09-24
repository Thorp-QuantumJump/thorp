import { useChartStable } from '@/components/charts/chart-context'
import { formatAxisAmount } from '@/components/result/formatResult'

/**
 * 세로축의 원화 금액 눈금(FR-001).
 *
 * <p>차트 시스템에는 세로축 컴포넌트가 없어 여기서 만든다. 눈금 위치는 차트가 들고 있는 y 스케일에
 * 맡기고, 같은 값을 가로 격자(`Grid`의 `rowTickValues`)에도 넘겨 글자와 선이 같은 높이에 놓이게 한다.
 *
 * <p><b>적는 금액과 놓을 자리를 나눠 받는다.</b> 로그 눈금으로 볼 때 차트의 y 스케일이 받는 값은 금액이
 * 아니라 금액의 상용로그다. 하나로 합치면 글자에 로그값이 찍히거나 선이 엉뚱한 높이에 놓인다.
 */

/** 눈금 하나. `amount`는 글자로 적을 원화 금액이고 `at`은 그 금액이 놓일 세로축 좌표값이다 */
export type AmountTick = {
  amount: number
  at: number
}

/**
 * 그림 영역 왼쪽 여백에 눈금 글자를 오른쪽 끝을 맞춰 적는다. `LineChart`의 자식으로만 쓴다.
 * `format`을 주면 금액 대신 그 글자를 적는다(하락 폭 띠의 % 눈금).
 */
export function AmountAxis({
  ticks,
  format = formatAxisAmount,
}: {
  ticks: AmountTick[]
  format?: (amount: number) => string
}) {
  const { yScale } = useChartStable()

  return (
    <g data-testid="amount-axis">
      {ticks.map((tick) => {
        const y = yScale(tick.at)
        if (y == null || !Number.isFinite(y)) return null
        return (
          <text
            dominantBaseline="middle"
            fill="var(--chart-label)"
            fontSize={11}
            key={tick.amount}
            textAnchor="end"
            x={-6}
            y={y}
          >
            {format(tick.amount)}
          </text>
        )
      })}
    </g>
  )
}

export default AmountAxis
