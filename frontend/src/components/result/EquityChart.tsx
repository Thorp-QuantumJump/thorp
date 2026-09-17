import { curveLinear } from '@visx/curve'
import { useMemo } from 'react'
import { Grid } from '@/components/charts/grid'
import { Line, LineChart } from '@/components/charts/line-chart'
import { XAxis } from '@/components/charts/x-axis'
import { DrawdownShade } from '@/components/result/DrawdownShade'
import { type DrawdownWindow, findDrawdownWindow } from '@/components/result/drawdownWindow'
import { formatMoney, formatRatio } from '@/components/result/formatResult'
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
 * <p><b>색에만 기대지 않는다</b>(SC-010). 기준은 실선, 비교 대상은 파선이고 두 곡선의 오른쪽 끝에
 * 이름표가 직접 붙는다. 하락 구간 음영도 칠한 면과 점선 경계를 함께 쓴다.
 *
 * <p>세로축은 시작 금액을 기준으로 잡는다. 0에서 시작하면 몇 %의 오르내림이 한 줄로 눌려 하락 구간을
 * 알아볼 수 없다.
 */

export type EquityChartSeries = {
  /** 곡선에 붙일 이름. 전략 이름이다 */
  name: string
  points: ResultTimeseriesPoint[]
  /** `summary.maxDrawdown`. 음영 구간 판정에 쓴다 */
  maxDrawdown: string | null
}

export type EquityChartProps = {
  base: EquityChartSeries
  /** 비교 대상. 없으면 곡선은 하나다 */
  target?: EquityChartSeries | null
  /** `config.initialCash`. 세로축의 기준이자 가로 기준선의 위치다 */
  initialCash: string
  /** 대안 텍스트에 담을 기준 결과의 핵심 숫자 3개. 계산되지 않았으면 `null` */
  altNumbers?: {
    netTotalReturn: string
    maxDrawdown: string
    totalTradingCost: string
  } | null
}

type ChartRow = {
  date: Date
  base?: number
  target?: number
}

const BASE_STROKE = 'var(--chart-line-primary)'
const TARGET_STROKE = 'var(--chart-line-secondary)'
/** 비교 대상 곡선의 파선 간격. 색을 못 봐도 선 모양으로 갈린다 */
const TARGET_DASH = '7,5'

function toNumber(value: string | null | undefined): number | null {
  if (value == null || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 날짜 문자열을 UTC 자정으로 읽는다. 차트의 x 접근자가 쓰는 해석과 같게 맞춘다 */
function toChartDate(date: string): Date {
  return new Date(date.length === 10 ? date + 'T00:00:00Z' : date)
}

/**
 * 두 계열을 날짜로 합쳐 한 그래프의 행으로 만든다.
 *
 * 값이 없는 날도 행은 남기고 그 열만 비운다. 비어 있어야 `defined`가 그 자리에서 선을 끊는다.
 */
function buildRows(base: ResultTimeseriesPoint[], target?: ResultTimeseriesPoint[]): ChartRow[] {
  const byDate = new Map<string, ChartRow>()

  const collect = (points: ResultTimeseriesPoint[], key: 'base' | 'target') => {
    for (const point of points) {
      const row = byDate.get(point.date) ?? { date: toChartDate(point.date) }
      const equity = toNumber(point.equity)
      if (equity != null) row[key] = equity
      byDate.set(point.date, row)
    }
  }

  collect(base, 'base')
  if (target) collect(target, 'target')

  return [...byDate.values()].sort((left, right) => left.date.getTime() - right.date.getTime())
}

/**
 * 세로축의 범위. 시작 금액을 아래끝으로 삼되 곡선이 그보다 더 내려간 적이 있으면 그만큼 넓힌다.
 * 위아래에 약간의 여백을 두어 시작 금액 기준선이 그림 가장자리에 붙어 잘리지 않게 한다.
 */
function resolveYDomain(rows: ChartRow[], initialCash: number): [number, number] {
  let lowest = initialCash
  let highest = initialCash
  for (const row of rows) {
    for (const value of [row.base, row.target]) {
      if (value == null) continue
      if (value < lowest) lowest = value
      if (value > highest) highest = value
    }
  }
  const span = highest - lowest
  const padding = span > 0 ? span * 0.08 : Math.max(Math.abs(highest) * 0.01, 1)
  return [lowest - padding, highest + padding]
}

/** 그래프를 문장으로 옮긴 대안 텍스트(FR-022) */
function buildAltText({ base, target, altNumbers }: EquityChartProps): string {
  const numbers = altNumbers
    ? '비용 차감 후 전체 수익률 ' +
      formatRatio(altNumbers.netTotalReturn) +
      ', 가장 크게 줄었던 폭 ' +
      formatRatio(altNumbers.maxDrawdown) +
      ', 거래비용 ' +
      formatMoney(altNumbers.totalTradingCost) +
      '입니다.'
    : '핵심 숫자가 계산되지 않아 곡선만 그렸습니다.'
  const comparison = target
    ? '비교 대상은 ' + target.name + '이며 파선으로 함께 그렸습니다.'
    : '비교 대상은 없습니다.'
  return base.name + '의 자산가치 곡선입니다. ' + numbers + ' ' + comparison
}

/** 음영 구간을 사람이 읽을 문장으로 옮긴다 */
function shadeNote(name: string, window: DrawdownWindow): string {
  return name + '의 음영은 가장 크게 줄었던 구간(' + window.peakDate + ' ~ ' + window.troughDate + ')입니다.'
}

export function EquityChart(props: EquityChartProps) {
  const { base, target, initialCash } = props
  const rows = useMemo(() => buildRows(base.points, target?.points), [base.points, target?.points])
  const initial = toNumber(initialCash)
  const yDomain = useMemo(() => resolveYDomain(rows, initial ?? 0), [rows, initial])

  const baseWindow = findDrawdownWindow(base.points, base.maxDrawdown)
  const targetWindow = target ? findDrawdownWindow(target.points, target.maxDrawdown) : null

  const notes = [
    initial == null ? null : '가로 점선은 시작 금액 ' + formatMoney(initialCash) + '입니다.',
    baseWindow ? shadeNote(base.name, baseWindow) : null,
    target && targetWindow ? shadeNote(target.name, targetWindow) : null,
  ].filter((note): note is string => note != null)

  // 그릴 값이 하나도 없으면 빈 축만 남는다. 빈 그래프는 "돌았고 비어 있었다"는 거짓이므로 그리지
  // 않고 자리만 비운다.
  if (rows.length === 0) return null

  const endLabels = target
    ? [
        { dataKey: 'base', name: base.name, stroke: BASE_STROKE },
        { dataKey: 'target', name: target.name, stroke: TARGET_STROKE },
      ]
    : [{ dataKey: 'base', name: base.name, stroke: BASE_STROKE }]

  return (
    <div className={styles.figure}>
      <div aria-label={buildAltText(props)} className={styles.chart} role="img">
        <LineChart
          animationDuration={0}
          aspectRatio=""
          className={styles.canvas}
          data={rows as unknown as Record<string, unknown>[]}
          decimate={false}
          margin={{ top: 18, right: 88, bottom: 30, left: 16 }}
          xDataKey="date"
          yDomain={yDomain}
          yDomainTween={false}
        >
          <Grid
            highlightRowStroke="var(--thorp-neutral)"
            highlightRowStrokeDasharray="6,4"
            highlightRowStrokeWidth={1.2}
            highlightRowValues={initial == null ? undefined : [initial]}
            horizontal
            numTicksRows={4}
            strokeDasharray="2,5"
          />

          {baseWindow ? (
            <DrawdownShade
              from={toChartDate(baseWindow.peakDate)}
              label={shadeNote(base.name, baseWindow)}
              to={toChartDate(baseWindow.troughDate)}
              variant="base"
            />
          ) : null}

          {target && targetWindow ? (
            <DrawdownShade
              from={toChartDate(targetWindow.peakDate)}
              label={shadeNote(target.name, targetWindow)}
              to={toChartDate(targetWindow.troughDate)}
              variant="target"
            />
          ) : null}

          <Line
            animate={false}
            curve={curveLinear}
            dataKey="base"
            defined={(row) => typeof row.base === 'number'}
            fadeEdges={false}
            showHighlight={false}
            stroke={BASE_STROKE}
            strokeWidth={2.4}
          />

          {target ? (
            <Line
              animate={false}
              curve={curveLinear}
              dataKey="target"
              defined={(row) => typeof row.target === 'number'}
              fadeEdges={false}
              showHighlight={false}
              stroke={TARGET_STROKE}
              strokeDasharray={TARGET_DASH}
              strokeWidth={2.4}
            />
          ) : null}

          <SeriesEndLabels series={endLabels} />

          <XAxis numTicks={4} tickMode="domain" />
        </LineChart>
      </div>

      {notes.length > 0 ? <p className={styles.notes}>{notes.join(' ')}</p> : null}
    </div>
  )
}

export default EquityChart
