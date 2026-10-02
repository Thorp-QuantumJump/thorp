import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { fetchMarketSeries } from '@/api/market'
import { fetchPriceSeries } from '@/api/sectors'
import type { MarketSeriesCode } from '@/types/marketApi'
import type { SectorSummary } from '@/types/sector'
import styles from './MarketNow.module.css'

/*
  투자 대상 고르기의 '지금 국내 시장'(002 FR-018, 009 FR-017). 대표 지수(KOSPI·KOSDAQ·VKOSPI 200·원/달러)와
  섹터마다의 대표 ETF 최근 종가를 카드 하나씩 띠 안에서 가로로 흘려 보인다. 비교 가능 기간 바로 위에 같은 폭으로
  놓인다. 과거 사례와 견주어 볼 출발점이지 경제환경 검색의 입력이 아니다.

  - 제목 아래에 기준일과 출처를 밝힌다(FR-015). 카드 아래에 줄을 따로 두면 띠의 높이만 커진다.
  - 한 카드에 이름·값·직전 거래일 대비 변화를 둔다. 값의 색은 오르내림이다(상승 빨강·하락 파랑, 좋고 나쁨이 아니다).
  - 지수 최신값 응답에는 직전 값이 없어 최근 흐름(`GET /markets/{code}/series`)의 마지막 두 거래일로 견준다.
    ETF는 대표 ETF 가격 흐름(`GET /sectors/{code}/price-series`)의 마지막 두 거래일 수정종가로 견준다.
  - 카드는 끝없이 왼쪽으로 흐르고, 마우스를 올리거나 초점이 들어오면 멈춘다. 움직임을 줄이도록 설정한 사용자에게는
    흐르지 않고 손으로 넘기는 띠가 된다.
  - 받지 못한 지표는 빼고, 하나도 받지 못하면 한 문장만 남긴다. 투자 대상 고르기는 막히지 않는다.
*/

const INDEX_CODES: { code: MarketSeriesCode; label: string }[] = [
  { code: 'KOSPI', label: 'KOSPI' },
  { code: 'KOSDAQ', label: 'KOSDAQ' },
  { code: 'VKOSPI200', label: 'VKOSPI 200' },
  { code: 'USD_KRW', label: '원/달러' },
]

/** ETF 가격 흐름 응답에는 출처 이름이 없다. 지수와 같은 KRX 자료이므로 같은 이름으로 밝힌다. */
const ETF_PROVIDER = 'KRX'

/** 국내 시장을 대표하는 반도체를 ETF 줄의 맨 앞에 둔다. 나머지는 화면의 표시 순서를 따른다. */
const LEAD_SECTOR_CODE = 'SEMICONDUCTOR'

/** 카드 하나가 화면을 가로지르는 데 드는 시간(초). 카드 수에 곱해 흐름 속도를 일정하게 둔다. */
const SECONDS_PER_CARD = 4

type Reading = {
  kind: 'index' | 'etf'
  key: string
  label: string
  value: number
  fractionDigits: number
  change: number | null
  lastDate: string
  provider: string
}

type State = { status: 'loading' } | { status: 'ready'; readings: Reading[] } | { status: 'unavailable' }

function toReading(
  kind: Reading['kind'],
  key: string,
  label: string,
  points: { date: string; value: number }[],
  provider: string,
  fractionDigits: number,
): Reading | null {
  const last = points[points.length - 1]
  if (!last || !Number.isFinite(last.value)) return null
  const previous = points[points.length - 2]
  const change = previous && previous.value !== 0 ? ((last.value - previous.value) / previous.value) * 100 : null
  return {
    kind,
    key,
    label,
    value: last.value,
    fractionDigits,
    change,
    lastDate: last.date,
    provider,
  }
}

async function readIndex(code: MarketSeriesCode, label: string): Promise<Reading | null> {
  const series = await fetchMarketSeries(code)
  return toReading('index', code, label, series.points, series.source.provider, 1)
}

async function readEtf(sector: SectorSummary, signal: AbortSignal): Promise<Reading | null> {
  const result = await fetchPriceSeries(sector.code, signal)
  if (result.status !== 'loaded') return null
  // 마지막 두 거래일만 쓴다. 전체 흐름을 숫자로 옮기지 않는다.
  const points = result.series.points
    .slice(-2)
    .map((point) => ({ date: point.date, value: Number(point.adjustedClose) }))
  return toReading('etf', `etf:${sector.code}`, sector.representativeEtf.name, points, ETF_PROVIDER, 0)
}

function leadFirst(sectors: readonly SectorSummary[]): SectorSummary[] {
  const lead = sectors.filter((sector) => sector.code === LEAD_SECTOR_CODE)
  return [...lead, ...sectors.filter((sector) => sector.code !== LEAD_SECTOR_CODE)]
}

function formatDate(date: string) {
  return date.replaceAll('-', '.')
}

/**
 * 지수와 ETF는 적재 주기가 달라 기준일이 어긋날 수 있다. 어긋나면 둘을 나눠 밝힌다. 가장 늦은 날짜 하나만
 * 적으면 앞선 날의 ETF 값이 그날 값처럼 읽힌다.
 */
function describeDates(readings: Reading[]) {
  const latestOf = (kind: Reading['kind']) =>
    readings
      .filter((reading) => reading.kind === kind)
      .map((reading) => reading.lastDate)
      .sort()
      .at(-1)
  const index = latestOf('index')
  const etf = latestOf('etf')
  if (index && etf && index !== etf) return `지수 ${formatDate(index)} · ETF ${formatDate(etf)} 기준`
  return `${formatDate(index ?? etf ?? '')} 기준`
}

function directionOf(change: number | null) {
  if (change === null) return undefined
  return change > 0 ? 'up' : change < 0 ? 'down' : undefined
}

function formatChange(change: number) {
  const sign = change > 0 ? '+' : change < 0 ? '−' : ''
  return `${sign}${Math.abs(change).toFixed(1)}%`
}

function Cards({ readings, hidden = false }: { readings: Reading[]; hidden?: boolean }) {
  return (
    <ul className={styles.cards} aria-hidden={hidden || undefined} data-copy={hidden ? 'repeat' : undefined}>
      {readings.map((reading) => (
        <li key={reading.key} className={styles.card}>
          <span className={styles.name}>{reading.label}</span>
          <span className={styles.reading} data-direction={directionOf(reading.change)}>
            <strong>
              {reading.value.toLocaleString('ko-KR', {
                maximumFractionDigits: reading.fractionDigits,
              })}
            </strong>
            {reading.change === null ? null : <em>{formatChange(reading.change)}</em>}
          </span>
        </li>
      ))}
    </ul>
  )
}

type MarketNowProps = {
  /** 띠의 제목. 출처·기준일이 그 바로 아래에 붙는다. */
  title: ReactNode
  /** 대표 ETF를 보일 섹터. 반도체를 맨 앞에 두고 나머지는 이 순서를 따른다. */
  sectors: readonly SectorSummary[]
}

export function MarketNow({ title, sectors }: MarketNowProps) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    // 응답마다 어느 지표인지 함께 싣는다. 결과 배열의 자리에 기대 이름을 붙이지 않는다.
    const requests = [
      ...INDEX_CODES.map((item) => readIndex(item.code, item.label)),
      ...leadFirst(sectors).map((sector) => readEtf(sector, controller.signal)),
    ]
    void Promise.allSettled(requests).then((results) => {
      if (!active) return
      const readings = results.flatMap((result) =>
        result.status === 'fulfilled' && result.value ? [result.value] : [],
      )
      setState(readings.length > 0 ? { status: 'ready', readings } : { status: 'unavailable' })
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [sectors])

  if (state.status !== 'ready') {
    return (
      <>
        <div className={styles.head}>{title}</div>
        {state.status === 'loading' ? (
          <p className={styles.note} role="status">
            국내 시장 자료를 불러오는 중입니다.
          </p>
        ) : (
          <p className={styles.note}>국내 시장 참고 정보를 지금은 불러올 수 없습니다.</p>
        )}
      </>
    )
  }

  const providers = [...new Set(state.readings.map((reading) => reading.provider))].join(', ')

  return (
    <>
      <div className={styles.head}>
        {title}
        <p className={styles.source}>{describeDates(state.readings)}</p>
        <p className={styles.source}>출처 {providers}</p>
      </div>
      {/* 같은 줄을 한 번 더 이어 붙여 끊김 없이 흐르게 한다. 뒤의 줄은 보조 기술에 숨긴다. */}
      <div className={styles.viewport} tabIndex={0} aria-label="국내 대표 지수와 ETF">
        <div
          className={styles.track}
          style={
            {
              '--ticker-duration': `${state.readings.length * SECONDS_PER_CARD}s`,
            } as CSSProperties
          }
        >
          <Cards readings={state.readings} />
          <Cards readings={state.readings} hidden />
        </div>
      </div>
    </>
  )
}
