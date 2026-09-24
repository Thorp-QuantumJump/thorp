import { curveLinear } from '@visx/curve'
import { useMemo } from 'react'
import type { Margin } from '@/components/charts/chart-context'
import { Grid } from '@/components/charts/grid'
import { Line, LineChart } from '@/components/charts/line-chart'
import { AmountAxis } from '@/components/result/AmountAxis'
import type { ResultTimeseriesPoint } from '@/types/result'
import styles from './EquityChart.module.css'

/** 띠 하나에 그릴 선. 본 그래프의 같은 선과 색·파선을 맞춘다 */
export type DrawdownStripSeries = {
  key: string
  stroke: string
  dash?: string
  width: number
  points: readonly Pick<ResultTimeseriesPoint, 'date' | 'drawdown'>[]
}

type StripRow = { date: Date } & Partial<Record<string, number | Date>>

/**
 * 하락 폭(언더워터) 띠. 날마다 그때까지의 최고점보다 몇 % 아래였는지를 0% 선 아래로 그린다.
 *
 * <p>값은 서버가 준 `drawdown`을 그대로 쓴다 — 화면이 최고점을 다시 세지 않는다. 그래서 서버가 하락 폭을 주지 않는
 * KOSPI 참고선은 여기에 없다.
 *
 * <p>본 그래프와 같은 좌우 여백·같은 날짜 범위를 받아 세로로 줄이 맞는다. 가로축 글자는 본 그래프가 이미 적으므로
 * 두지 않는다.
 */
export function DrawdownStrip({
  series,
  from,
  to,
  margin,
}: {
  series: readonly DrawdownStripSeries[]
  from: Date
  to: Date
  margin: Pick<Margin, 'left' | 'right'>
}) {
  const rows = useMemo(() => {
    const byDate = new Map<string, StripRow>()
    for (const { key, points } of series) {
      for (const point of points) {
        const date = new Date(point.date.length === 10 ? point.date + 'T00:00:00Z' : point.date)
        if (date < from || date > to) continue
        const value = Number(point.drawdown)
        if (point.drawdown == null || point.drawdown === '' || !Number.isFinite(value)) continue
        const row = byDate.get(point.date) ?? { date }
        row[key] = value * 100
        byDate.set(point.date, row)
      }
    }
    return [...byDate.values()].sort((left, right) => left.date.getTime() - right.date.getTime())
  }, [series, from, to])

  const deepest = Math.min(
    0,
    ...rows.flatMap((row) =>
      series.map((line) => row[line.key]).filter((value): value is number => typeof value === 'number'),
    ),
  )
  if (rows.length < 2) return null
  // 하락이 없으면 0% 한 줄만 남으므로 아래로 1%의 자리를 둔다.
  const low = deepest < 0 ? deepest * 1.08 : -1
  const ticks = [
    { amount: 0, at: 0 },
    ...(deepest < 0 ? [{ amount: deepest, at: deepest }] : []),
  ]

  return (
    <div className={styles.drawdownStrip}>
      <span className={styles.drawdownStripLabel}>하락 폭</span>
      <LineChart
        animationDuration={0}
        aspectRatio=""
        className={styles.canvas}
        data={rows as unknown as Record<string, unknown>[]}
        decimate={false}
        margin={{ top: 8, right: margin.right, bottom: 8, left: margin.left }}
        xDataKey="date"
        yDomain={[low, 0]}
        yDomainTween={false}
      >
        <Grid
          highlightRowStroke="var(--thorp-neutral)"
          highlightRowStrokeWidth={1}
          highlightRowValues={[0]}
          horizontal
          rowTickValues={ticks.map((tick) => tick.at)}
          strokeDasharray="2,5"
        />
        <AmountAxis format={(value) => (value === 0 ? '0%' : value.toFixed(1) + '%')} ticks={ticks} />
        {series.map((line) => (
          <Line
            animate={false}
            curve={curveLinear}
            dataKey={line.key}
            defined={(row) => typeof row[line.key] === 'number'}
            fadeEdges={false}
            key={line.key}
            showHighlight={false}
            stroke={line.stroke}
            strokeDasharray={line.dash}
            strokeWidth={line.width}
          />
        ))}
      </LineChart>
    </div>
  )
}
