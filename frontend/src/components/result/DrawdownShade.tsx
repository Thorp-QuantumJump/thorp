import { useId } from 'react'
import { useChartStable } from '@/components/charts/chart-context'

/**
 * 최대 하락 구간 음영(FR-002, R-09).
 *
 * <p><b>색만으로 구간을 알리지 않는다.</b> 칠한 면과 함께 위쪽 경계에 점선을 그어, 회색조로 보아도
 * 어디부터 어디까지가 하락 구간인지 드러나게 한다. 비교 대상의 음영은 빗금으로 채워 기준과 구분한다.
 *
 * <p>날짜를 화소로 옮기는 일은 차트가 이미 들고 있는 x 스케일에 맡긴다. 그래서 이 컴포넌트는
 * `LineChart`의 자식으로만 쓸 수 있고, 부모가 `Date`로 바꿔 넘긴 두 날짜만 받는다.
 */
export type DrawdownShadeProps = {
  /** 구간의 시작(운용 중 가장 높았던 날) */
  from: Date
  /** 구간의 끝(그 뒤 가장 낮았던 날) */
  to: Date
  /** 기준 결과인가 비교 대상인가. 비교 대상은 빗금으로 채운다 */
  variant?: 'base' | 'target'
  /** 대안 텍스트가 따로 있으므로 그림 자체는 보조 기술에 노출하지 않는다 */
  label?: string
}

export function DrawdownShade({ from, to, variant = 'base', label }: DrawdownShadeProps) {
  const { xScale, innerWidth, innerHeight } = useChartStable()
  const patternId = `drawdown-hatch-${useId()}`

  const start = xScale(from)
  const end = xScale(to)
  if (start == null || end == null || !Number.isFinite(start) || !Number.isFinite(end)) {
    return null
  }

  const left = Math.max(0, Math.min(start, end))
  const right = Math.min(innerWidth, Math.max(start, end))
  const width = right - left
  if (!(width > 0) || !(innerHeight > 0)) return null

  const fill = variant === 'target' ? `url(#${patternId})` : 'var(--result-shade-fill)'

  return (
    <g data-drawdown-shade={variant} data-testid={`drawdown-shade-${variant}`}>
      {variant === 'target' ? (
        <defs>
          <pattern
            id={patternId}
            width={6}
            height={6}
            patternTransform="rotate(45)"
            patternUnits="userSpaceOnUse"
          >
            <line
              stroke="var(--result-shade-edge)"
              strokeWidth={1.2}
              x1={0}
              x2={0}
              y1={0}
              y2={6}
            />
          </pattern>
        </defs>
      ) : null}

      <rect fill={fill} height={innerHeight} width={width} x={left} y={0}>
        {label ? <title>{label}</title> : null}
      </rect>

      {/* 위쪽 경계선. 색을 구별하지 못해도 이 점선으로 구간의 범위를 읽는다. */}
      <line
        data-testid={`drawdown-shade-edge-${variant}`}
        stroke="var(--result-shade-edge)"
        strokeDasharray="5,4"
        strokeWidth={1.4}
        x1={left}
        x2={right}
        y1={0}
        y2={0}
      />
    </g>
  )
}

export default DrawdownShade
