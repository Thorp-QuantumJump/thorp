import { useChartStable } from '@/components/charts/chart-context'
import { formatAxisAmount } from '@/components/result/formatResult'

/**
 * 세로축의 원화 금액 눈금(FR-001).
 *
 * <p>차트 시스템에는 세로축 컴포넌트가 없어 여기서 만든다. 눈금 위치는 차트가 들고 있는 y 스케일에
 * 맡기고, 같은 값을 가로 격자(`Grid`의 `rowTickValues`)에도 넘겨 글자와 선이 같은 높이에 놓이게 한다.
 */

/** 그림 영역 왼쪽 여백에 눈금 글자를 오른쪽 끝을 맞춰 적는다. `LineChart`의 자식으로만 쓴다 */
export function AmountAxis({ values }: { values: number[] }) {
  const { yScale } = useChartStable()

  return (
    <g data-testid="amount-axis">
      {values.map((value) => {
        const y = yScale(value)
        if (y == null || !Number.isFinite(y)) return null
        return (
          <text
            dominantBaseline="middle"
            fill="var(--chart-label)"
            fontSize={11}
            key={value}
            textAnchor="end"
            x={-6}
            y={y}
          >
            {formatAxisAmount(value)}
          </text>
        )
      })}
    </g>
  )
}

export default AmountAxis
