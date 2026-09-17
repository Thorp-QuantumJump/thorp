import { useChartStable } from '@/components/charts/chart-context'

/**
 * 곡선 오른쪽 끝에 직접 붙는 이름표(R-09, SC-010).
 *
 * <p><b>범례만으로 두 곡선을 구분하지 않는다.</b> 범례는 색을 보고 곡선을 되짚어 찾는 단계를
 * 요구하는데, 색을 구별하지 못하면 그 단계에서 막힌다. 이름을 곡선 끝에 붙이면 그 단계가 없어진다.
 *
 * <p>차트 시스템의 `line-series-terminal-marker.tsx`는 끝점에 점만 찍으므로 여기서 새로 만든다.
 */

/** 이름표 하나가 필요로 하는 값 */
export type SeriesEndLabelItem = {
  /** 차트 행에서 값을 꺼낼 열 이름 */
  dataKey: string
  /** 보일 이름 */
  name: string
  /** 곡선과 같은 색 */
  stroke: string
}

/** 두 이름표가 겹쳐 읽히지 않도록 유지하는 세로 간격(화소) */
const MIN_GAP = 16

/** 오른쪽 여백에 들어가는 글자 수. 넘치면 줄이고 전체 이름은 `<title>`에 남긴다 */
const MAX_CHARS = 9

type PlacedLabel = SeriesEndLabelItem & { x: number; y: number }

function shorten(name: string): string {
  return name.length > MAX_CHARS ? name.slice(0, MAX_CHARS - 1) + '…' : name
}

/**
 * 이름표 하나. 위치는 부모가 정해 넘긴다 — 겹침은 이름표 하나가 혼자 풀 수 없다.
 */
export function SeriesEndLabel({ label }: { label: PlacedLabel }) {
  const shortened = shorten(label.name)

  return (
    <g data-testid={'series-end-label-' + label.dataKey}>
      <line
        stroke={label.stroke}
        strokeWidth={1}
        x1={label.x}
        x2={label.x + 6}
        y1={label.y}
        y2={label.y}
      />
      <text
        dominantBaseline="middle"
        fill={label.stroke}
        fontSize={12}
        x={label.x + 9}
        y={label.y}
      >
        {shortened === label.name ? null : <title>{label.name}</title>}
        {shortened}
      </text>
    </g>
  )
}

/**
 * 곡선마다 마지막 값이 있는 날을 찾아 그 자리에 이름표를 붙이고, 겹치면 위아래로 민다.
 */
export function SeriesEndLabels({ series }: { series: SeriesEndLabelItem[] }) {
  const { data, xScale, yScale, xAccessor, innerHeight } = useChartStable()

  const placed: PlacedLabel[] = []
  for (const item of series) {
    for (let index = data.length - 1; index >= 0; index -= 1) {
      const value = data[index][item.dataKey]
      if (typeof value !== 'number' || !Number.isFinite(value)) continue
      const x = xScale(xAccessor(data[index]))
      const y = yScale(value)
      if (x == null || y == null || !Number.isFinite(x) || !Number.isFinite(y)) break
      placed.push({ ...item, x, y })
      break
    }
  }
  if (placed.length === 0) return null

  return (
    <g data-testid="series-end-labels">
      {spreadApart(placed, innerHeight).map((label) => (
        <SeriesEndLabel key={label.dataKey} label={label} />
      ))}
    </g>
  )
}

/**
 * 이름표를 위에서 아래 순으로 훑으며 최소 간격을 지키게 아래로 밀고, 그러다 바닥을 넘으면 묶음
 * 전체를 그만큼 위로 되돌린다. 원래 자리에서 조금 멀어지더라도 두 이름이 겹쳐 읽히지 않는 편이 낫다.
 */
function spreadApart(labels: PlacedLabel[], innerHeight: number): PlacedLabel[] {
  const sorted = [...labels].sort((left, right) => left.y - right.y)
  for (let index = 1; index < sorted.length; index += 1) {
    const gap = sorted[index].y - sorted[index - 1].y
    if (gap < MIN_GAP) {
      sorted[index] = { ...sorted[index], y: sorted[index - 1].y + MIN_GAP }
    }
  }

  const overflow = sorted[sorted.length - 1].y - innerHeight
  if (overflow > 0) {
    return sorted.map((label) => ({ ...label, y: label.y - overflow }))
  }
  return sorted
}

export default SeriesEndLabels
