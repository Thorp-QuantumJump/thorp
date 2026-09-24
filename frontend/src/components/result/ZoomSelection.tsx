import { useEffect, useRef } from 'react'
import { useChartHover, useChartStable } from '@/components/charts/chart-context'

/** 이보다 좁게 끌면 누른 것으로 보고 확대하지 않는다(화소) */
const MIN_DRAG_PX = 8

/**
 * 끄는 동안 고른 구간을 칠하고, 손을 떼면 그 구간의 두 날짜를 알린다.
 *
 * <p>끌기 판정은 차트의 상호작용(`selection`)이 이미 한다. 차트는 손을 뗄 때 선택을 지우기만 하므로, 여기서
 * 마지막 선택을 기억해 두었다가 창의 `mouseup`에서 넘긴다. 그래프 밖에서 손을 떼도 확대된다.
 *
 * <p>`LineChart`의 자식으로만 쓴다.
 */
export function ZoomSelection({ onZoom }: { onZoom: (range: [Date, Date]) => void }) {
  const { xScale, innerHeight } = useChartStable()
  const { selection } = useChartHover()
  const latest = useRef<{ startX: number; endX: number } | null>(null)
  const onZoomRef = useRef(onZoom)

  useEffect(() => {
    onZoomRef.current = onZoom
  }, [onZoom])

  // 선택이 비는 순간에는 지우지 않는다. 차트가 mouseup 에서 선택을 비우고 그 렌더가 창의 mouseup 보다 먼저
  // 끝날 수 있어서, 여기서 지우면 넘길 구간이 사라진다. 넘긴 뒤에 `release`가 지운다.
  useEffect(() => {
    if (selection?.active) latest.current = { startX: selection.startX, endX: selection.endX }
  }, [selection])

  useEffect(() => {
    const release = () => {
      const picked = latest.current
      latest.current = null
      if (!picked || picked.endX - picked.startX < MIN_DRAG_PX) return
      onZoomRef.current([xScale.invert(picked.startX), xScale.invert(picked.endX)])
    }
    window.addEventListener('mouseup', release)
    return () => window.removeEventListener('mouseup', release)
  }, [xScale])

  if (!selection?.active || selection.endX - selection.startX < MIN_DRAG_PX) return null
  return (
    <rect
      aria-hidden="true"
      data-testid="zoom-selection"
      fill="var(--chart-line-primary)"
      fillOpacity={0.12}
      height={innerHeight}
      pointerEvents="none"
      stroke="var(--chart-line-primary)"
      strokeDasharray="4,3"
      strokeOpacity={0.6}
      width={selection.endX - selection.startX}
      x={selection.startX}
      y={0}
    />
  )
}
