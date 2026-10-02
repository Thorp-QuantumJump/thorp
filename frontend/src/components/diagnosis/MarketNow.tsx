import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { fetchMarketSeries } from '@/api/market'
import { fetchLatestPrices } from '@/api/sectors'
import { KRX_SOURCE_ATTRIBUTION } from '@/diagnosis/attribution'
import { orderSectorsForMarketStrip } from '@/diagnosis/sectorOrder'
import type { MarketSeriesCode } from '@/types/marketApi'
import type { LatestPrice, SectorSummary } from '@/types/sector'
import styles from './MarketNow.module.css'

/*
  투자 대상 고르기의 '지금 국내 시장'(002 FR-018, 009 FR-017). 대표 지수(KOSPI·KOSDAQ·VKOSPI 200·원/달러)와
  섹터마다의 대표 ETF 최근 종가를 카드 하나씩 띠 안에 가로로 보인다. 비교 가능 기간 바로 위에 같은 폭으로
  놓인다. 과거 사례와 견주어 볼 출발점이지 경제환경 검색의 입력이 아니다.

  - 제목 아래에 기준일과 출처를 밝힌다(FR-015). 카드 아래에 줄을 따로 두면 띠의 높이만 커진다.
  - 한 카드에 이름·값·직전 거래일 대비 변화를 둔다. 값의 색은 오르내림이다(상승 빨강·하락 파랑, 좋고 나쁨이 아니다).
  - 지수 최신값 응답에는 직전 값이 없어 최근 흐름(`GET /markets/{code}/series`)의 마지막 두 거래일로 견준다.
    ETF는 최근 가격(`GET /sectors/latest-prices`) 한 번으로 마지막 값과 직전 값을 받는다.
  - 마지막 날이 거래정지일이거나 직전 값을 옮겨 적은 날이면 등락률 대신 그 사실을 적는다. 옮겨 적은 값을 그날
    거래된 값처럼 보이지 않는다(원칙 V).
  - 카드가 창을 넘칠 때만 끝없이 왼쪽으로 흐르고, 마우스를 올리거나 초점이 들어오면 멈춘다. 넘치지 않으면 흐르지
    않는다. 움직임을 줄이도록 설정한 사용자에게는 흐르지 않고 손으로 넘기는 띠가 된다.
  - 지수와 ETF는 서로 기다리지 않고 도착하는 대로 보인다. 받지 못한 지표는 빼고, 하나도 받지 못하면 한 문장만
    남긴다. 투자 대상 고르기는 막히지 않는다.
*/

const INDEX_CODES: { code: MarketSeriesCode; label: string }[] = [
  { code: 'KOSPI', label: 'KOSPI' },
  { code: 'KOSDAQ', label: 'KOSDAQ' },
  { code: 'VKOSPI200', label: 'VKOSPI 200' },
  { code: 'USD_KRW', label: '원/달러' },
]

/** 카드 하나가 화면을 가로지르는 데 드는 시간(초). 카드 수에 곱해 흐름 속도를 일정하게 둔다. */
const SECONDS_PER_CARD = 4

type Reading = {
  kind: 'index' | 'etf'
  key: string
  label: string
  value: number
  fractionDigits: number
  change: number | null
  /** 등락률 대신 적을 사정. 거래정지일이거나 직전 값을 옮겨 적은 날이다. */
  flag: string | null
  lastDate: string
  provider: string
}

/** ETF 줄은 어느 섹터 목록으로 받은 것인지 함께 둔다. 목록이 바뀌면 새 응답이 올 때까지 이전 카드를 보이지 않는다. */
type EtfSlot = { sectors: readonly SectorSummary[]; readings: Reading[] }

function changeBetween(last: number, previous: number | undefined) {
  return previous !== undefined && previous !== 0 ? ((last - previous) / previous) * 100 : null
}

async function readIndex(code: MarketSeriesCode, label: string, signal: AbortSignal): Promise<Reading | null> {
  const series = await fetchMarketSeries(code, signal)
  const last = series.points.at(-1)
  if (!last || !Number.isFinite(last.value)) return null
  return {
    kind: 'index',
    key: code,
    label,
    value: last.value,
    fractionDigits: 1,
    change: changeBetween(last.value, series.points.at(-2)?.value),
    flag: null,
    lastDate: last.date,
    provider: series.source.provider,
  }
}

function flagOf(price: LatestPrice) {
  if (price.tradingSuspended) return '거래정지'
  if (price.priceCarriedForward) return '직전 값'
  return null
}

function toEtfReading(price: LatestPrice): Reading {
  const value = Number(price.adjustedClose)
  const flag = flagOf(price)
  const previous = price.previousAdjustedClose === null ? undefined : Number(price.previousAdjustedClose)
  return {
    kind: 'etf',
    key: `etf:${price.sectorCode}`,
    label: price.name,
    value,
    fractionDigits: 0,
    change: flag === null ? changeBetween(value, previous) : null,
    flag,
    lastDate: price.date,
    provider: KRX_SOURCE_ATTRIBUTION,
  }
}

async function readEtfs(sectors: readonly SectorSummary[], signal: AbortSignal): Promise<Reading[]> {
  const result = await fetchLatestPrices(signal)
  if (result.status !== 'loaded') return []
  const byCode = new Map(result.latest.prices.map((price) => [price.sectorCode, price]))
  return orderSectorsForMarketStrip(sectors).flatMap((sector) => {
    const price = byCode.get(sector.code)
    return price ? [toEtfReading(price)] : []
  })
}

function formatDate(date: string) {
  return date.replaceAll('-', '.')
}

/**
 * 지수와 ETF는 적재 주기가 달라 기준일이 어긋날 수 있다. 어긋나면 둘을 나눠 밝힌다. 가장 늦은 날짜 하나만
 * 적으면 앞선 날의 ETF 값이 그날 값처럼 읽힌다. 밝힐 날짜가 하나도 없으면 null이다. 빈 날짜로 '기준'만 남기지 않는다.
 */
function describeDates(readings: Reading[]): string | null {
  const latestOf = (kind: Reading['kind']) =>
    readings
      .filter((reading) => reading.kind === kind)
      .map((reading) => reading.lastDate)
      .filter((date) => date !== '')
      .sort()
      .at(-1)
  const index = latestOf('index')
  const etf = latestOf('etf')
  if (index && etf && index !== etf) return `지수 ${formatDate(index)} · ETF ${formatDate(etf)} 기준`
  const only = index ?? etf
  return only ? `${formatDate(only)} 기준` : null
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
            {reading.flag !== null ? (
              <em data-flag="">{reading.flag}</em>
            ) : reading.change === null ? null : (
              <em>{formatChange(reading.change)}</em>
            )}
          </span>
        </li>
      ))}
    </ul>
  )
}

/**
 * 한 줄이 창보다 넓은지 잰다. 넓을 때만 두 벌을 이어 흘린다. 좁은데 흘리면 창 오른쪽이 비고, 한 바퀴마다
 * 카드가 처음 자리로 튄다. 그 뒤 창이나 카드 폭이 바뀌면 다시 잰다.
 */
function useOverflowing(active: boolean, cardCount: number) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const [overflowing, setOverflowing] = useState(false)

  useLayoutEffect(() => {
    const viewport = viewportRef.current
    const list = listRef.current
    if (!active || !viewport || !list) return
    const measure = () => setOverflowing(list.scrollWidth > viewport.clientWidth)
    // 처음 한 번은 그리기 전에 직접 잰다. 관찰자의 첫 알림은 숨은 탭에서 늦거나 오지 않는다.
    measure()
    // 창 크기 이벤트도 함께 받는다. 관찰자 알림이 오지 않는 환경에서도 창을 넓히고 좁힐 때 다시 잰다.
    window.addEventListener('resize', measure)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(viewport)
    observer?.observe(list)
    return () => {
      window.removeEventListener('resize', measure)
      observer?.disconnect()
    }
  }, [active, cardCount])

  return { viewportRef, listRef, overflowing }
}

type MarketNowProps = {
  /** 띠의 제목. 기준일·출처가 그 바로 아래에 붙는다. */
  title: ReactNode
  /** 대표 ETF를 보일 섹터. 반도체를 맨 앞에 두고 나머지는 이 순서를 따른다. */
  sectors: readonly SectorSummary[]
}

export function MarketNow({ title, sectors }: MarketNowProps) {
  // null은 아직 도착하지 않은 상태다. 빈 배열은 도착했지만 보일 것이 없는 상태다.
  const [indexReadings, setIndexReadings] = useState<Reading[] | null>(null)
  const [etfSlot, setEtfSlot] = useState<EtfSlot | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    // 응답마다 어느 지수인지 함께 싣는다. 결과 배열의 자리에 기대 이름을 붙이지 않는다.
    void Promise.allSettled(INDEX_CODES.map((item) => readIndex(item.code, item.label, controller.signal))).then(
      (results) => {
        if (controller.signal.aborted) return
        setIndexReadings(
          results.flatMap((result) => (result.status === 'fulfilled' && result.value ? [result.value] : [])),
        )
      },
    )
    return () => controller.abort()
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void readEtfs(sectors, controller.signal)
      .catch(() => [])
      .then((readings) => {
        if (!controller.signal.aborted) setEtfSlot({ sectors, readings })
      })
    return () => controller.abort()
  }, [sectors])

  const etfReadings = etfSlot?.sectors === sectors ? etfSlot.readings : null
  const readings = [...(indexReadings ?? []), ...(etfReadings ?? [])]
  const settled = indexReadings !== null && etfReadings !== null
  const ready = readings.length > 0
  const { viewportRef, listRef, overflowing } = useOverflowing(ready, readings.length)

  if (!ready) {
    return (
      <>
        <div className={styles.head}>{title}</div>
        {settled ? (
          <p className={styles.note}>국내 시장 참고 정보를 지금은 불러올 수 없습니다.</p>
        ) : (
          <p className={styles.note} role="status">
            국내 시장 자료를 불러오는 중입니다.
          </p>
        )}
      </>
    )
  }

  const dates = describeDates(readings)
  const providers = [...new Set(readings.map((reading) => reading.provider))].join(', ')

  return (
    <>
      <div className={styles.head}>
        {title}
        {dates === null ? null : <p className={styles.source}>{dates}</p>}
        <p className={styles.source}>출처 {providers}</p>
      </div>
      {/* 넘칠 때만 같은 줄을 한 번 더 이어 붙여 끊김 없이 흐르게 한다. 뒤의 줄은 보조 기술에 숨긴다. */}
      <div
        ref={viewportRef}
        className={styles.viewport}
        role="region"
        tabIndex={0}
        aria-label="국내 대표 지수와 ETF"
        data-flow={overflowing ? 'on' : 'off'}
      >
        <div
          className={styles.track}
          style={{ '--ticker-duration': `${readings.length * SECONDS_PER_CARD}s` } as CSSProperties}
        >
          <div ref={listRef} className={styles.copy}>
            <Cards readings={readings} />
          </div>
          {overflowing ? <Cards readings={readings} hidden /> : null}
        </div>
      </div>
    </>
  )
}
