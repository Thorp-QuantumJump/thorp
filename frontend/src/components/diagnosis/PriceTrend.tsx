import { curveLinear } from '@visx/curve'
import { useMemo } from 'react'
import { Grid } from '@/components/charts/grid'
import { Line, LineChart } from '@/components/charts/line-chart'
import { XAxis } from '@/components/charts/x-axis'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import type { PriceSeriesResult } from '@/api/sectors'
import type { PriceSeries } from '@/types/sector'

type PriceTrendProps = {
  /** 무엇의 흐름인지. 그림을 소리로만 듣는 사용자에게 읽어 줄 이름이다. */
  name: string
  result: PriceSeriesResult | null
  onReload: () => void
  /**
   * 카드가 넓혀져 있는가.
   *
   * 그림의 높이를 가로 폭에 대한 비율로 잡기 때문에 이 값이 필요하다. 넓힌 카드는 가운데 칸이
   * 좁아지므로 같은 비율을 쓰면 그림만 납작해진다 — 자세히 보려고 넓혔는데 선이 더 안 보이게 된다.
   */
  expanded?: boolean
}

/**
 * 카드가 보여 주는 가격 흐름이다.
 *
 * <b>기준선을 0에 두지 않는다.</b> 0에서 시작하면 몇 %의 오르내림이 한 줄로 눌려 이 상품이 어떻게
 * 움직여 왔는지가 보이지 않는다. 눈금은 실제 값의 범위에 맞춘다.
 *
 * <b>값이 없는 날의 앞뒤를 잇지 않는다.</b> 이어 그리면 없는 가격을 만들어 낸다(원칙 VI). 거래가
 * 멈춰 직전 값을 옮겨 적은 날은 값이 있는 날이므로 선을 끊지 않되, 그런 날이 있었다는 사실을 아래
 * 한 줄로 알린다 — 그 구간의 평평한 선은 "값이 움직이지 않았다"가 아니라 "거래가 없었다"이다.
 *
 * <b>세로축 눈금을 적지 않는다.</b> 이 자리에서 사용자가 묻는 것은 "얼마였나"가 아니라 "어떻게
 * 움직여 왔나"다. 정확한 금액이 필요한 자리는 결과 화면이고 거기에는 금액 축이 있다.
 */
export function PriceTrend({ name, result, onReload, expanded = false }: PriceTrendProps) {
  if (result === null) {
    return (
      <div data-slot="chart" data-state="loading">
        <p role="status">가격 흐름을 불러오는 중입니다.</p>
      </div>
    )
  }

  /*
    자료가 아직 없는 상태다. **빈 그래프를 보이지 않는다.** 점 없는 선은 "값이 없다"가 아니라
    "움직임이 없었다"로 읽힌다. 파이프라인이 적재하면 달라지는 상태라 다시 불러오기를 준다.
  */
  if (result.status === 'notReady') {
    return (
      <div data-slot="chart" data-state="empty">
        <NotReadyNotice
          message="이 상품의 가격 자료가 아직 준비 중입니다."
          action={{ label: '다시 불러오기', onAction: onReload }}
        />
      </div>
    )
  }

  if (result.status === 'failed') {
    return (
      <div data-slot="chart" data-state="empty">
        <NotReadyNotice
          message="가격 흐름을 불러오지 못했습니다."
          action={result.retryable ? { label: '다시 불러오기', onAction: onReload } : undefined}
        />
      </div>
    )
  }

  return <TrendChart name={name} series={result.series} expanded={expanded} />
}

type ChartRow = {
  date: Date
  close: number
}

function TrendChart({
  name,
  series,
  expanded,
}: {
  name: string
  series: PriceSeries
  expanded: boolean
}) {
  const rows = useMemo<ChartRow[]>(
    () =>
      series.points.map((point) => ({
        date: new Date(`${point.date}T00:00:00+09:00`),
        close: Number(point.adjustedClose),
      })),
    [series],
  )

  // 거래가 멈춰 직전 값을 옮겨 적은 날이다. 그 구간의 평평한 선을 설명하지 않으면 오해가 남는다.
  const carriedForwardCount = useMemo(
    () => series.points.filter((point) => point.priceCarriedForward).length,
    [series],
  )

  const first = rows[0]
  const last = rows[rows.length - 1]

  return (
    <div data-slot="chart" data-state="loaded">
      {/*
        그림 자체를 보조 기술에 설명한다. 축 눈금이 없으므로 기간과 양 끝 값을 말로 실어 준다 —
        그것이 이 선에서 읽히는 전부다.
      */}
      <div
        data-slot="chart-canvas"
        role="img"
        aria-label={`${name}의 가격 흐름. ${toDateText(first.date)}부터 ${toDateText(
          last.date,
        )}까지 ${rows.length}거래일.`}
      >
        <LineChart
          animationDuration={0}
          // 높이를 비율로 잡는다. 바깥 칸의 높이를 물려받게 두면 크기를 재는 쪽이 0을 보고
          // 아무것도 그리지 않는 순간이 생긴다. 넓힌 카드는 칸이 좁아지므로 비율을 키운다.
          aspectRatio={expanded ? '5 / 3' : '3 / 1'}
          data={rows as unknown as Record<string, unknown>[]}
          decimate={false}
          margin={{ top: 10, right: 10, bottom: 22, left: 10 }}
          xDataKey="date"
          yDomainTween={false}
        >
          <Grid horizontal strokeDasharray="2,5" />
          <Line
            animate={false}
            curve={curveLinear}
            dataKey="close"
            defined={(row) => typeof row.close === 'number'}
            fadeEdges={false}
            showHighlight={false}
            stroke="var(--thorp-accent)"
            strokeWidth={1.8}
          />
          <XAxis numTicks={4} tickMode="domain" />
        </LineChart>
      </div>

      <p data-slot="chart-note">
        {toDateText(first.date)} ~ {toDateText(last.date)} · 수정종가
        {carriedForwardCount > 0
          ? ` · 거래가 없어 직전 가격을 이어 붙인 날 ${carriedForwardCount}일이 섞여 있습니다.`
          : null}
        {series.isSample ? ' · 아직 확정되지 않은 예시 자료입니다.' : null}
      </p>
    </div>
  )
}

/** 축과 설명이 같은 형식을 쓰도록 한곳에서 옮긴다. */
function toDateText(at: Date): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(at)
    .replace(/\.\s*$/, '')
    .replace(/\.\s*/g, '-')
}
