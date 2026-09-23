import { useChartStable } from '@/components/charts/chart-context'

/**
 * 곡선 오른쪽 끝에 직접 붙는 이름표(R-09, SC-010).
 *
 * <p><b>범례만으로 두 곡선을 구분하지 않는다.</b> 범례는 색을 보고 곡선을 되짚어 찾는 단계를
 * 요구하는데, 색을 구별하지 못하면 그 단계에서 막힌다. 이름을 곡선 끝에 붙이면 그 단계가 없어진다.
 *
 * <p><b>이름을 자르지 않는다.</b> 등록표의 전략 이름은 7~17자라 오른쪽 여백 한 줄에 들어가지 않는다.
 * 잘라 보이면 두 곡선이 어느 전략인지 읽을 수 없으므로, 여백 폭에 맞춰 단어 단위로 여러 줄로 나눈다.
 * 줄 수만큼 이름표의 높이가 달라지므로 겹침도 높이를 셈해 푼다.
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

const FONT_SIZE = 11
const LINE_HEIGHT = 13
/** 곡선 끝에서 글자까지의 거리. 앞의 6px에는 곡선과 이름표를 잇는 짧은 선이 들어간다 */
const TEXT_OFFSET = 9
/** 여백 오른쪽 끝에 남기는 틈. 글자가 그림 가장자리에 붙어 잘리지 않게 한다 */
const EDGE_PADDING = 2
/** 두 이름표 사이에 두는 세로 틈(화소) */
const BLOCK_GAP = 4

/**
 * 글자 폭 어림. 한글·한자는 글자 크기만큼, 띄어쓰기는 그 4할, 나머지는 6할 남짓으로 잡는다.
 *
 * 앱 글꼴(Pretendard)과 대체 글꼴(맑은 고딕)로 11px에서 재 보면 한글은 글자 크기와 같거나 조금 좁고,
 * 띄어쓰기는 맑은 고딕에서 0.35배 남짓이다. 어림을 실제보다 조금 넓게 잡아야 줄이 여백을 넘지 않는다.
 */
const WIDE_CHAR = /[\u1100-\u11ff\u3130-\u318f\uac00-\ud7af\u3000-\u9fff]/

function estimateWidth(text: string): number {
  let width = 0
  for (const char of text) {
    if (char === ' ') width += FONT_SIZE * 0.4
    else if (WIDE_CHAR.test(char)) width += FONT_SIZE
    else width += FONT_SIZE * 0.62
  }
  return width
}

/**
 * 이름을 주어진 폭 안에 들어가는 줄들로 나눈다. 단어 사이에서 먼저 끊고, 한 단어가 폭을 넘을 때만
 * 글자 사이에서 끊는다. 글자를 버리지 않는다.
 */
function wrapName(name: string, maxWidth: number): string[] {
  const lines: string[] = []
  let current = ''

  for (const word of name.trim().split(/\s+/)) {
    const candidate = current ? current + ' ' + word : word
    if (estimateWidth(candidate) <= maxWidth) {
      current = candidate
      continue
    }
    if (current) lines.push(current)
    if (estimateWidth(word) <= maxWidth) {
      current = word
      continue
    }
    let piece = ''
    for (const char of word) {
      if (piece && estimateWidth(piece + char) > maxWidth) {
        lines.push(piece)
        piece = char
      } else {
        piece += char
      }
    }
    current = piece
  }
  if (current) lines.push(current)
  return lines
}

type PlacedLabel = SeriesEndLabelItem & {
  /** 곡선 끝점 */
  x: number
  y: number
  lines: string[]
  /** 이름표 묶음의 위끝. 겹침을 푼 뒤의 값이다 */
  top: number
}

function blockHeight(label: Pick<PlacedLabel, 'lines'>): number {
  return label.lines.length * LINE_HEIGHT
}

/**
 * 이름표 하나. 위치는 부모가 정해 넘긴다 — 겹침은 이름표 하나가 혼자 풀 수 없다. 이름표가 곡선 끝에서
 * 밀려났으면 짧은 선이 곡선 끝과 이름표 가운데를 잇는다.
 */
export function SeriesEndLabel({ label }: { label: PlacedLabel }) {
  const center = label.top + blockHeight(label) / 2

  return (
    <g data-testid={'series-end-label-' + label.dataKey}>
      <line
        stroke={label.stroke}
        strokeWidth={1}
        x1={label.x}
        x2={label.x + 6}
        y1={label.y}
        y2={center}
      />
      <text dominantBaseline="middle" fill={label.stroke} fontSize={FONT_SIZE}>
        {label.lines.map((line, index) => (
          <tspan
            key={index}
            x={label.x + TEXT_OFFSET}
            y={label.top + LINE_HEIGHT * index + LINE_HEIGHT / 2}
          >
            {line}
          </tspan>
        ))}
      </text>
    </g>
  )
}

/**
 * 곡선마다 마지막 값이 있는 날을 찾아 그 자리에 이름표를 붙이고, 겹치면 위아래로 민다.
 */
export function SeriesEndLabels({ series }: { series: SeriesEndLabelItem[] }) {
  const { data, xScale, yScale, xAccessor, innerHeight, margin } = useChartStable()
  const textWidth = Math.max(margin.right - TEXT_OFFSET - EDGE_PADDING, FONT_SIZE)

  const placed: PlacedLabel[] = []
  for (const item of series) {
    for (let index = data.length - 1; index >= 0; index -= 1) {
      const value = data[index][item.dataKey]
      if (typeof value !== 'number' || !Number.isFinite(value)) continue
      const x = xScale(xAccessor(data[index]))
      const y = yScale(value)
      if (x == null || y == null || !Number.isFinite(x) || !Number.isFinite(y)) break
      const lines = wrapName(item.name, textWidth)
      placed.push({ ...item, x, y, lines, top: y - (lines.length * LINE_HEIGHT) / 2 })
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
 * 이름표 묶음을 위에서 아래 순으로 훑으며 앞 묶음의 아래끝보다 아래로 밀고, 그러다 그림 영역 바닥을
 * 넘으면 전체를 그만큼 위로 되돌린다. 그래도 위끝이 영역을 벗어나면 다시 아래로 붙인다. 바닥 아래에는
 * 가로축 날짜가 있어 그 선을 넘지 않게 한다. 원래 자리에서 조금 멀어지더라도 두 이름이 겹쳐 읽히지
 * 않는 편이 낫다.
 */
function spreadApart(labels: PlacedLabel[], innerHeight: number): PlacedLabel[] {
  const sorted = [...labels].sort((left, right) => left.top - right.top)
  for (let index = 1; index < sorted.length; index += 1) {
    const previous = sorted[index - 1]
    const floor = previous.top + blockHeight(previous) + BLOCK_GAP
    if (sorted[index].top < floor) sorted[index] = { ...sorted[index], top: floor }
  }

  const last = sorted[sorted.length - 1]
  const overflow = last.top + blockHeight(last) - innerHeight
  const lifted = overflow > 0 ? sorted.map((label) => ({ ...label, top: label.top - overflow })) : sorted

  const underflow = -lifted[0].top
  return underflow > 0 ? lifted.map((label) => ({ ...label, top: label.top + underflow })) : lifted
}

export default SeriesEndLabels
