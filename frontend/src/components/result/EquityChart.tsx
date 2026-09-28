import { curveLinear } from '@visx/curve'
import { ticks } from 'd3-array'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Grid } from '@/components/charts/grid'
import { Line, LineChart } from '@/components/charts/line-chart'
import { ChartTooltip, TooltipContent, type TooltipRow } from '@/components/charts/tooltip'
import { XAxis } from '@/components/charts/x-axis'
import { AmountAxis, type AmountTick } from '@/components/result/AmountAxis'
import { DrawdownShade } from '@/components/result/DrawdownShade'
import { type DrawdownWindow, findDrawdownWindow } from '@/components/result/drawdownWindow'
import { hasDrawableEquity, readEquity } from '@/components/result/equityPoints'
import { formatMoney, formatRatio } from '@/components/result/formatResult'
import { InitialCashLabel } from '@/components/result/InitialCashLabel'
import { InlineHelp } from '@/components/common/InlineHelp'
import { SeriesEndLabels } from '@/components/result/SeriesEndLabel'
import type { ResultTimeseriesPoint } from '@/types/result'
import styles from './EquityChart.module.css'

/**
 * 결과 첫 화면의 그래프 하나(FR-001, FR-002).
 *
 * <p><b>받은 점을 줄이지 않는다</b>(`decimate={false}`, R-08). 점을 줄이면 가장 낮았던 날이 빠져
 * 곡선이 '가장 크게 줄었던 폭' 숫자보다 얕아 보인다. 값이 없는 날은 앞뒤를 잇지 않고 선을 끊는다 —
 * 이어 그리면 없는 자산가치를 만들어 낸다(원칙 VI).
 *
 * <p><b>색에만 기대지 않는다</b>(SC-010). 기준은 실선, 비교 대상은 각기 다른 파선이고 모든 곡선의 오른쪽 끝에
 * 이름표가 직접 붙는다. 하락 구간 음영도 칠한 면과 점선 경계를 함께 쓴다.
 *
 * <p><b>참고 지수선(KOSPI)은 비교 대상과 따로다</b>(FR-017). 서버가 시작 금액으로 환산한 값을 그대로 가늘고 옅은 실선으로 그리고
 * 끝 이름표를 붙인다. 셋의 상한에 세지 않고, 음영을 칠하지 않는다 — 전략 결과가 아니라 참고 정보다.
 *
 * <p><b>비교 대상은 셋까지다</b>(`S15P21E205-419`). 넷을 넘으면 선 모양과 색으로 갈리지 않는다. 비교 대상의 하락 구간
 * 음영은 대상이 하나일 때만 칠한다 — 둘 이상이면 빗금이 겹쳐 어느 구간이 누구 것인지 읽을 수 없다.
 *
 * <p>세로축은 시작 금액을 기준으로 잡는다. 0에서 시작하면 몇 %의 오르내림이 한 줄로 눌려 하락 구간을
 * 알아볼 수 없다. 눈금은 만·억 단위 원화로 넷 이하만 적어 곡선의 높이를 금액으로 읽게 한다. 시작 금액
 * 자리에는 가로 점선과 함께 금액을 적은 이름표를 붙여, 곡선이 원금 위에 있는지 아래에 있는지를 그래프
 * 안에서 바로 읽게 한다.
 *
 * <p><b>세로축 눈금은 로그가 기본이고 선형으로 바꿀 수 있다.</b> 로그 눈금에서는 같은 비율의 오르내림이
 * 구간과 관계없이 같은 높이로 보이므로, 기간이 길어 자산가치가 여러 배로 불어난 결과에서도 초반의 하락
 * 구간이 납작하게 눌리지 않는다. 대신 같은 금액 차이가 구간마다 다른 높이로 보이기 때문에, 금액 차이를
 * 그대로 견주려는 사람을 위해 선형 눈금을 함께 남긴다. 어느 쪽이든 세로축에 적는 값은 원화 자산가치이고
 * (FR-001) 바뀌는 것은 그 금액을 놓는 높이뿐이다.
 */

export type EquityChartSeries = {
  /** 곡선에 붙일 이름. 전략 이름이다 */
  name: string
  points: ResultTimeseriesPoint[]
  /** `summary.maxDrawdown`. 음영 구간 판정에 쓴다 */
  maxDrawdown: string | null
}

/** 참고 지수선. 값은 서버가 시작 금액으로 환산한 금액 문자열이다 */
export type EquityChartReference = {
  name: string
  points: readonly { date: string; value: string }[]
}

export type EquityChartProps = {
  base: EquityChartSeries
  /** 비교 대상들. 없으면 곡선은 하나다. 셋을 넘는 것은 그리지 않는다 */
  targets?: readonly EquityChartSeries[] | null
  /** 참고 지수선. 비교 대상 상한과 따로 센다 */
  reference?: EquityChartReference | null
  /** `config.initialCash`. 세로축의 기준이자 가로 기준선의 위치다 */
  initialCash: string
  /** 대안 텍스트에 담을 기준 결과의 핵심 숫자 3개. 계산되지 않았으면 `null` */
  altNumbers?: {
    netTotalReturn: string
    maxDrawdown: string
    totalTradingCost: string
  } | null
}

/** 세로축 눈금을 금액 그대로 놓을지(`linear`) 금액의 상용로그로 놓을지(`log`) 정한다 */
export type YScaleMode = 'linear' | 'log'

type ChartRow = { date: Date } & Partial<Record<string, number | Date>>

const BASE_KEY = 'base'
const BASE_STROKE = 'var(--chart-line-primary)'
const REFERENCE_KEY = 'reference'
/** 참고선은 옅은 글자색의 가는 실선이다. 전략 곡선(굵은 실선·파선)과 모양으로 갈린다 */
const REFERENCE_STROKE = 'var(--muted-foreground)'
/**
 * 비교 대상의 색과 파선. 자리 순서대로 쓴다. 파선 간격이 서로 달라 색을 못 봐도 선 모양으로 갈린다.
 * 넷째 대상은 없다 — 상한이 셋이다.
 */
const TARGET_STYLES = [
  { stroke: 'var(--result-line-secondary)', dash: '7,5' },
  { stroke: 'var(--result-line-tertiary)', dash: '2,4' },
  { stroke: 'var(--result-line-quaternary)', dash: '10,4,2,4' },
] as const
/** 세로축 눈금은 많아야 넷이다. 더 늘리면 첫 화면이 숫자로 붐빈다(원칙 II) */
const MAX_AMOUNT_TICKS = 4
/** 처음 보여 줄 눈금 종류 */
const DEFAULT_Y_SCALE_MODE: YScaleMode = 'log'

/** 비교 대상 i번째의 행 열 이름(`target0`…). 시험이 이 이름의 색 변수로 곡선을 찾는다 */
function targetKey(index: number): string {
  return 'target' + index
}

function toNumber(value: string | null | undefined): number | null {
  if (value == null || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 금액을 세로축 좌표값으로 옮긴다.
 *
 * <p>로그 눈금에서 0원 이하는 상용로그가 정의되지 않는다. 그런 날은 `null`을 돌려주어 그 자리에서
 * 곡선을 끊는다. 아주 작은 값으로 바꿔 이어 그리면 있지도 않은 바닥을 만들어 낸다(원칙 VI).
 */
function toAxisValue(amount: number, mode: YScaleMode): number | null {
  if (mode === 'linear') return amount
  return amount > 0 ? Math.log10(amount) : null
}

/** 날짜 문자열을 UTC 자정으로 읽는다. 차트의 x 접근자가 쓰는 해석과 같게 맞춘다 */
function toChartDate(date: string): Date {
  return new Date(date.length === 10 ? date + 'T00:00:00Z' : date)
}

/** 확대해도 이보다 적은 거래일은 보이지 않는다 */
const MIN_VIEW_ROWS = 10

/** 보이는 구간. 전체 행 가운데 [처음, 끝] 번째(양끝 포함)다. `null`이면 전체 기간이다 */
type ViewRange = [number, number] | null

/**
 * 휠 한 번의 확대·축소. `anchor`(0~1)는 커서가 그림 폭의 어디에 있는가이고, 그 자리의 날짜가 제자리에 남도록
 * 구간을 넓히거나 좁힌다. 전체보다 넓어지면 전체 기간(`null`)으로 돌아간다.
 */
function zoomView(view: ViewRange, total: number, anchor: number, factor: number): ViewRange {
  if (total <= MIN_VIEW_ROWS) return null
  const [start, end] = view ?? [0, total - 1]
  const span = end - start
  const nextSpan = Math.round(Math.max(MIN_VIEW_ROWS - 1, span * factor))
  if (nextSpan >= total - 1) return null
  const pivot = start + anchor * span
  const nextStart = Math.max(0, Math.min(total - 1 - nextSpan, Math.round(pivot - anchor * nextSpan)))
  return [nextStart, nextStart + nextSpan]
}

/** 끌어서 옮기기. 구간 폭은 그대로 두고 양끝을 넘지 않는다 */
function panView(view: ViewRange, total: number, shift: number): ViewRange {
  if (!view) return null
  const span = view[1] - view[0]
  const start = Math.max(0, Math.min(total - 1 - span, view[0] + shift))
  return [start, start + span]
}

/** 툴팁이 읽을 원래 금액의 열 이름. 곡선 열은 로그 눈금이면 금액이 아니라 좌표값이다 */
function amountKey(key: string): string {
  return key + ':amount'
}

/** 툴팁 제목. 기간이 여러 해라 연도를 늘 적는다. 행의 날짜는 UTC 자정이다 */
const tooltipDateFormat = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'short',
  timeZone: 'UTC',
})

/** 시작 금액 대비 변화. 부호를 늘 붙인다 */
function changeFromInitial(amount: number, initial: number | null): string {
  if (initial == null || initial <= 0) return ''
  const text = formatRatio(String(amount / initial - 1))
  return text.startsWith('-') ? text : '+' + text
}

/**
 * 계열들을 날짜로 합쳐 한 그래프의 행으로 만든다. 값은 눈금 종류에 맞춘 세로축 좌표값으로 담는다.
 * 툴팁을 위해 원래 금액도 `amountKey` 열에 함께 담는다.
 *
 * 값이 없는 날도 행은 남기고 그 열만 비운다. 비어 있어야 `defined`가 그 자리에서 선을 끊는다.
 */
function buildRows(
  series: readonly { key: string; points: readonly Pick<ResultTimeseriesPoint, 'date' | 'equity'>[] }[],
  mode: YScaleMode,
): ChartRow[] {
  const byDate = new Map<string, ChartRow>()
  for (const { key, points } of series) {
    for (const point of points) {
      const row = byDate.get(point.date) ?? { date: toChartDate(point.date) }
      const equity = readEquity(point)
      const axisValue = equity == null ? null : toAxisValue(equity, mode)
      if (axisValue != null) row[key] = axisValue
      if (equity != null) row[amountKey(key)] = equity
      byDate.set(point.date, row)
    }
  }
  return [...byDate.values()].sort((left, right) => left.date.getTime() - right.date.getTime())
}

/**
 * 세로축의 범위. 시작 금액을 아래끝으로 삼되 곡선이 그보다 더 내려간 적이 있으면 그만큼 넓힌다.
 * 위아래에 약간의 여백을 두어 시작 금액 기준선이 그림 가장자리에 붙어 잘리지 않게 한다.
 *
 * <p>시작 금액을 읽을 수 없으면 곡선의 값만으로 범위를 잡는다. 0을 대신 넣으면 곡선이 그림 위쪽에
 * 한 줄로 눌려 하락 구간을 알아볼 수 없다.
 *
 * <p>받는 값과 돌려주는 값은 모두 세로축 좌표값이다. 로그 눈금에서는 로그 공간에서 여백을 잡으므로,
 * 곡선이 한 점뿐일 때 쓰는 최소 여백도 금액이 아니라 로그 폭으로 정한다. 금액 기준의 최소 여백인 1원을
 * 로그 공간에 그대로 쓰면 열 배 폭이 되어 곡선이 그림 한가운데 선으로 눌린다.
 */
function resolveYDomain(
  rows: ChartRow[],
  keys: readonly string[],
  initialAxisValue: number | null,
  mode: YScaleMode,
): [number, number] {
  let lowest = initialAxisValue ?? Number.POSITIVE_INFINITY
  let highest = initialAxisValue ?? Number.NEGATIVE_INFINITY
  for (const row of rows) {
    for (const key of keys) {
      const value = row[key]
      if (typeof value !== 'number') continue
      if (value < lowest) lowest = value
      if (value > highest) highest = value
    }
  }
  if (!Number.isFinite(lowest) || !Number.isFinite(highest)) {
    return mode === 'log' ? [0, 2] : [0, 100]
  }
  const span = highest - lowest
  const minimumPadding = mode === 'log' ? 0.01 : 1
  const padding = span > 0 ? span * 0.08 : Math.max(Math.abs(highest) * 0.01, minimumPadding)
  return [lowest - padding, highest + padding]
}

/**
 * 세로축 범위에서 둥근 눈금 값을 고른다. d3의 개수 인자는 힌트라 넷보다 많이 돌려줄 수 있으므로, 넷
 * 이하가 될 때까지 힌트를 줄인다. 같은 값을 격자선과 눈금 글자에 함께 넘겨 둘이 같은 높이에 놓이게 한다.
 */
function pickAmountTicks([low, high]: [number, number]): number[] {
  if (!Number.isFinite(low) || !Number.isFinite(high) || !(high > low)) return []
  for (let count = MAX_AMOUNT_TICKS; count >= 1; count -= 1) {
    const values = ticks(low, high, count)
    if (values.length <= MAX_AMOUNT_TICKS) return values
  }
  return []
}

/** 눈금이 넷을 넘으면 양끝을 남기고 고르게 솎아 낸다 */
function thinTicks(values: number[]): number[] {
  if (values.length <= MAX_AMOUNT_TICKS) return values
  const step = (values.length - 1) / (MAX_AMOUNT_TICKS - 1)
  const picked: number[] = []
  for (let index = 0; index < MAX_AMOUNT_TICKS; index += 1) {
    picked.push(values[Math.round(index * step)])
  }
  return [...new Set(picked)]
}

/**
 * 로그 눈금에서 글자로 적을 금액. 1·2·5에 10의 거듭제곱을 곱한 값 가운데 범위에 드는 것을 먼저 찾는다.
 * 자산가치가 오르내린 폭이 좁으면 그런 값이 하나도 걸리지 않아 축이 비므로, 그때는 선형과 같은 둥근
 * 금액을 쓴다. 어느 쪽이든 금액을 놓는 높이는 로그로 잡히므로 곡선과 눈금은 어긋나지 않는다.
 */
function pickLogAmountTicks([lowLog, highLog]: [number, number]): number[] {
  if (!Number.isFinite(lowLog) || !Number.isFinite(highLog) || !(highLog > lowLog)) return []
  const low = 10 ** lowLog
  const high = 10 ** highLog
  const rounded: number[] = []
  for (let exponent = Math.floor(lowLog); exponent <= Math.ceil(highLog); exponent += 1) {
    for (const multiple of [1, 2, 5]) {
      const amount = multiple * 10 ** exponent
      if (amount >= low && amount <= high) rounded.push(amount)
    }
  }
  if (rounded.length >= 2) return thinTicks(rounded)
  return pickAmountTicks([low, high])
}

/** 눈금 금액에 놓일 자리를 붙인다. 좌표로 옮길 수 없는 금액은 눈금에서 뺀다 */
function toAmountTicks(amounts: number[], mode: YScaleMode): AmountTick[] {
  const result: AmountTick[] = []
  for (const amount of amounts) {
    const at = toAxisValue(amount, mode)
    if (at != null) result.push({ amount, at })
  }
  return result
}

/** 눈금 종류를 사람이 읽을 말로 옮긴다 */
function scaleModeNoun(mode: YScaleMode): string {
  return mode === 'log' ? '로그' : '선형'
}

/** 그래프를 문장으로 옮긴 대안 텍스트(FR-022) */
function buildAltText(
  base: EquityChartSeries,
  targets: readonly EquityChartSeries[],
  reference: EquityChartReference | null,
  altNumbers: EquityChartProps['altNumbers'],
  initialCash: string,
  mode: YScaleMode,
): string {
  const numbers = altNumbers
    ? '비용 차감 후 전체 수익률 ' +
      formatRatio(altNumbers.netTotalReturn) +
      ', 가장 크게 줄었던 폭 ' +
      formatRatio(altNumbers.maxDrawdown) +
      ', 거래비용 ' +
      formatMoney(altNumbers.totalTradingCost) +
      '입니다.'
    : '핵심 숫자가 계산되지 않아 곡선만 그렸습니다.'
  const comparison =
    targets.length > 0
      ? '비교 대상은 ' +
        targets.map((target) => target.name).join(', ') +
        (targets.length === 1 ? '이며 파선으로' : '이며 서로 다른 파선으로') +
        ' 함께 그렸습니다.'
      : '비교 대상은 없습니다.'
  const referenceNote = reference
    ? ' 참고로 ' + reference.name + '를 시작 금액으로 환산한 선을 가는 실선으로 함께 그렸습니다.'
    : ''
  const axis =
    '세로축은 원화 자산가치이고 눈금은 ' +
    scaleModeNoun(mode) +
    '이며, 시작 금액은 ' +
    formatMoney(initialCash) +
    '입니다.'
  return (
    base.name + '의 자산가치 곡선입니다. ' + numbers + ' ' + comparison + referenceNote + ' ' + axis
  )
}

/** 음영 구간을 사람이 읽을 문장으로 옮긴다 */
function shadeNote(name: string, window: DrawdownWindow): string {
  return name + '의 음영은 가장 크게 줄었던 구간(' + window.peakDate + ' ~ ' + window.troughDate + ')입니다.'
}

export function EquityChart(props: EquityChartProps) {
  const { base, initialCash, altNumbers } = props
  const reference = props.reference ?? null
  const targets = useMemo(
    () => (props.targets ?? []).slice(0, TARGET_STYLES.length),
    [props.targets],
  )
  const [scaleMode, setScaleMode] = useState<YScaleMode>(DEFAULT_Y_SCALE_MODE)
  const [compact, setCompact] = useState(() => window.innerWidth <= 600)
  useEffect(() => {
    const update = () => setCompact(window.innerWidth <= 600)
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  const keyed = useMemo(
    () => [
      { key: BASE_KEY, points: base.points },
      ...targets.map((target, index) => ({ key: targetKey(index), points: target.points })),
      ...(reference
        ? [
            {
              key: REFERENCE_KEY,
              points: reference.points.map((point) => ({ date: point.date, equity: point.value })),
            },
          ]
        : []),
    ],
    [base.points, targets, reference],
  )
  // 구분표에서 숨긴 선. 행에서는 빼지 않는다 — 빼면 날짜 집합이 바뀌어 확대 구간이 흔들린다.
  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set())
  const shown = (key: string) => !hidden.has(key)
  const keys = keyed.map((series) => series.key).filter(shown)
  const allRows = useMemo(() => buildRows(keyed, scaleMode), [keyed, scaleMode])
  // 휠로 좁힌 구간. 그 구간의 행만 차트에 넘기므로 세로축·끝 이름표·툴팁이 모두 그 구간 기준이 된다.
  const [view, setView] = useState<ViewRange>(null)
  const zoomed = view !== null && view[1] < allRows.length
  const rows = useMemo(
    () => (zoomed ? allRows.slice(view[0], view[1] + 1) : allRows),
    [allRows, view, zoomed],
  )
  // 아래 여백은 두 줄(연·월) 눈금 글자가 최대 하락 음영과 붙지 않게 한 칸 띄운다(S15P21E205-451).
  const margin = { top: 18, right: compact ? 18 : 88, bottom: 58, left: compact ? 55 : 58 }
  const chartRef = useRef<HTMLDivElement>(null)
  const [dragging, setDragging] = useState(false)
  // 휠·끌기 처리기는 한 번만 붙인다. 처리기가 읽는 값은 여기에 담아 늘 최신으로 둔다.
  const live = useRef({ view, total: allRows.length, margin })
  live.current = { view: zoomed ? view : null, total: allRows.length, margin }

  // 그래프 안의 휠은 그래프가 쓰고 페이지를 굴리지 않는다. React 의 onWheel 은 passive 라 막을 수 없어 직접 붙인다.
  useEffect(() => {
    const node = chartRef.current
    if (!node) return
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY === 0) return
      event.preventDefault()
      const { view: current, total, margin: m } = live.current
      const box = node.getBoundingClientRect()
      const plot = box.width - m.left - m.right
      const anchor = plot > 0 ? Math.min(1, Math.max(0, (event.clientX - box.left - m.left) / plot)) : 0.5
      setView(zoomView(current, total, anchor, Math.exp(event.deltaY * 0.002)))
    }
    node.addEventListener('wheel', onWheel, { passive: false })
    return () => node.removeEventListener('wheel', onWheel)
  }, [])

  // 확대한 동안에만 끌어서 옮긴다. 옮긴 거리는 누른 자리 기준이라 조금씩 끌어도 쌓이는 오차가 없다.
  const startPan = (event: React.PointerEvent<HTMLDivElement>) => {
    const origin = live.current.view
    const node = chartRef.current
    if (!origin || !node || event.button !== 0) return
    const { total, margin: m } = live.current
    const plot = node.getBoundingClientRect().width - m.left - m.right
    const span = origin[1] - origin[0]
    const startX = event.clientX
    setDragging(true)
    const move = (moveEvent: PointerEvent) => {
      if (plot <= 0) return
      setView(panView(origin, total, Math.round(((startX - moveEvent.clientX) / plot) * span)))
    }
    const end = () => {
      setDragging(false)
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', end)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', end)
  }
  const initial = toNumber(initialCash)
  const initialAxisValue = initial == null ? null : toAxisValue(initial, scaleMode)
  const yDomain = useMemo(
    () => resolveYDomain(rows, keys, initialAxisValue, scaleMode),
    // keys 는 keyed·hidden 에서 나오므로 그 둘로 갈음한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [rows, keyed, hidden, initialAxisValue, scaleMode],
  )
  const amountTicks = useMemo(() => {
    const amounts = scaleMode === 'log' ? pickLogAmountTicks(yDomain) : pickAmountTicks(yDomain)
    return toAmountTicks(amounts, scaleMode)
  }, [yDomain, scaleMode])

  const baseWindow = shown(BASE_KEY) ? findDrawdownWindow(base.points, base.maxDrawdown) : null
  // 비교 대상의 음영은 하나일 때만. 둘 이상이면 빗금이 겹쳐 누구 구간인지 읽을 수 없다.
  const targetWindow =
    targets.length === 1 && shown(targetKey(0))
      ? findDrawdownWindow(targets[0].points, targets[0].maxDrawdown)
      : null

  // 그릴 값이 하나도 없으면 빈 축만 남는다. 빈 그래프는 "돌았고 비어 있었다"는 거짓이므로 그리지
  // 않는다. 그 사실을 알리는 문장은 그래프 자리(`ResultPage`)가 같은 규칙으로 판단해 둔다.
  if (!hasDrawableEquity(base.points) && !targets.some((target) => hasDrawableEquity(target.points)))
    return null

  const endLabels = [
    { dataKey: BASE_KEY, name: base.name, stroke: BASE_STROKE },
    ...targets.map((target, index) => ({
      dataKey: targetKey(index),
      name: target.name,
      stroke: TARGET_STYLES[index].stroke,
    })),
    ...(reference ? [{ dataKey: REFERENCE_KEY, name: reference.name, stroke: REFERENCE_STROKE }] : []),
  ].filter((label) => shown(label.dataKey))
  const seriesKey = [
    { dataKey: BASE_KEY, name: base.name, stroke: BASE_STROKE, dash: undefined, kind: '기준' },
    ...targets.map((target, index) => ({
      dataKey: targetKey(index),
      name: target.name,
      stroke: TARGET_STYLES[index].stroke,
      dash: TARGET_STYLES[index].dash as string | undefined,
      kind: '비교',
    })),
    ...(reference
      ? [
          {
            dataKey: REFERENCE_KEY,
            name: reference.name,
            stroke: REFERENCE_STROKE,
            dash: undefined,
            kind: '참고',
          },
        ]
      : []),
  ]
  const visibleCount = seriesKey.filter((series) => shown(series.dataKey)).length
  const toggleSeries = (key: string) =>
    setHidden((known) => {
      const next = new Set(known)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  return (
    <div className={styles.figure}>
      <div className={styles.headingRow}>
        <h2 className={styles.heading}>
          자산가치 추이
          <InlineHelp label="확대" side="bottom">
            그래프 위에서 휠을 굴리면 커서 자리를 기준으로 확대·축소하고, 확대한 뒤에는 끌어서 옮깁니다. 두 번
            누르거나 '전체 기간'을 누르면 돌아옵니다.
          </InlineHelp>
        </h2>
        <div className={styles.headingActions}>
          {zoomed ? (
            <button className={styles.zoomReset} onClick={() => setView(null)} type="button">
              전체 기간
            </button>
          ) : null}
          <div aria-label="세로축 눈금" className={styles.scaleToggle} role="group">
            {(['linear', 'log'] as const).map((mode) => (
              <button
                aria-pressed={scaleMode === mode}
                className={styles.scaleButton}
                key={mode}
                onClick={() => setScaleMode(mode)}
                type="button"
              >
                {scaleModeNoun(mode)}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div aria-label="그래프 선 구분" className={styles.seriesKey} role="group">
        {seriesKey.map((series) => {
          const visible = shown(series.dataKey)
          return (
            <button
              aria-pressed={visible}
              className={styles.seriesKeyItem}
              // 마지막 남은 선은 숨기지 않는다. 빈 그래프는 아무것도 말하지 않는다.
              disabled={visible && visibleCount === 1}
              key={series.dataKey}
              onClick={() => toggleSeries(series.dataKey)}
              title={visible ? '누르면 이 선을 숨깁니다' : '누르면 이 선을 다시 보입니다'}
              type="button"
            >
              <svg aria-hidden="true" className={styles.seriesKeySwatch} viewBox="0 0 30 12">
                <line
                  stroke={series.stroke}
                  strokeDasharray={series.dash}
                  strokeWidth={series.kind === '기준' ? 3.2 : series.kind === '참고' ? 1.4 : 2}
                  x1="1"
                  x2="29"
                  y1="6"
                  y2="6"
                />
              </svg>
              <span className={styles.seriesKind}>{series.kind}</span>
              <span className={styles.seriesName}>{series.name}</span>
            </button>
          )
        })}
      </div>

      <div
        aria-label={buildAltText(base, targets, reference, altNumbers, initialCash, scaleMode)}
        className={styles.chart}
        data-pan={zoomed ? (dragging ? 'dragging' : 'ready') : undefined}
        onDoubleClick={() => setView(null)}
        onPointerDown={startPan}
        ref={chartRef}
        role="img"
      >
        <LineChart
          animationDuration={0}
          aspectRatio=""
          className={styles.canvas}
          data={rows as unknown as Record<string, unknown>[]}
          decimate={false}
          margin={margin}
          xDataKey="date"
          yDomain={yDomain}
          yDomainTween={false}
        >
          <Grid
            highlightRowStroke="var(--thorp-neutral)"
            highlightRowStrokeDasharray="6,4"
            highlightRowStrokeWidth={1.2}
            highlightRowValues={initialAxisValue == null ? undefined : [initialAxisValue]}
            horizontal
            rowTickValues={amountTicks.map((tick) => tick.at)}
            strokeDasharray="2,5"
          />

          <AmountAxis ticks={amountTicks} />

          {initial == null || initialAxisValue == null ? null : (
            <InitialCashLabel amount={initial} at={initialAxisValue} />
          )}

          {baseWindow ? (
            <DrawdownShade
              from={toChartDate(baseWindow.peakDate)}
              label={shadeNote(base.name, baseWindow)}
              to={toChartDate(baseWindow.troughDate)}
              variant="base"
            />
          ) : null}

          {targetWindow ? (
            <DrawdownShade
              from={toChartDate(targetWindow.peakDate)}
              label={shadeNote(targets[0].name, targetWindow)}
              to={toChartDate(targetWindow.troughDate)}
              variant="target"
            />
          ) : null}

          {reference && shown(REFERENCE_KEY) ? (
            <Line
              animate={false}
              curve={curveLinear}
              dataKey={REFERENCE_KEY}
              defined={(row) => typeof row[REFERENCE_KEY] === 'number'}
              fadeEdges={false}
              showHighlight={false}
              stroke={REFERENCE_STROKE}
              strokeWidth={1.4}
            />
          ) : null}

          {targets.map((_, index) => {
            const key = targetKey(index)
            if (!shown(key)) return null
            return (
              <Line
                animate={false}
                curve={curveLinear}
                dataKey={key}
                defined={(row) => typeof row[key] === 'number'}
                fadeEdges={false}
                key={key}
                showHighlight={false}
                stroke={TARGET_STYLES[index].stroke}
                strokeDasharray={TARGET_STYLES[index].dash}
                strokeWidth={2}
              />
            )
          })}

          {shown(BASE_KEY) ? (
            <Line
              animate={false}
              curve={curveLinear}
              dataKey={BASE_KEY}
              defined={(row) => typeof row[BASE_KEY] === 'number'}
              fadeEdges={false}
              showHighlight={false}
              stroke={BASE_STROKE}
              strokeWidth={3.2}
            />
          ) : null}

          {compact ? null : <SeriesEndLabels series={endLabels} />}

          <XAxis numTicks={4} stackYear tickMode="domain" />

          {/* 날짜 하나에 모든 선의 자산가치와 시작 금액 대비 변화를 한 상자에 보인다. 값은 받은 금액 그대로다. */}
          <ChartTooltip
            content={({ point }) => (
              <TooltipContent
                rows={endLabels.flatMap((series): TooltipRow[] => {
                  const amount = point[amountKey(series.dataKey)]
                  if (typeof amount !== 'number') return []
                  const change = changeFromInitial(amount, initial)
                  return [
                    {
                      color: series.stroke,
                      label: series.name,
                      value: formatMoney(String(amount)) + (change ? ' (' + change + ')' : ''),
                    },
                  ]
                })}
                title={tooltipDateFormat.format(point.date as Date)}
              />
            )}
            showDatePill={false}
          />
        </LineChart>
      </div>

      <div className={styles.legend}>
        {initial == null ? null : <span>점선 · 시작 금액 {formatMoney(initialCash)}</span>}
        {baseWindow && base.maxDrawdown ? (
          <span>음영 · 최대 하락 구간 {formatRatio(base.maxDrawdown)}</span>
        ) : null}
        {reference ? <span>가는 실선 · {reference.name} 시작 금액 환산(배당·비용 제외)</span> : null}
      </div>
    </div>
  )
}

export default EquityChart
