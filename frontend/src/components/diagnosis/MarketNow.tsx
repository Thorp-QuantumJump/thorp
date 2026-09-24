import { useEffect, useState } from 'react'
import { fetchMarketSeries } from '@/api/market'
import type { MarketSeriesCode } from '@/types/marketApi'
import styles from './MarketNow.module.css'

/*
  투자 대상 고르기 오른쪽의 '지금 국내 시장'(002 FR-018, 009 FR-017). KOSPI와 VKOSPI 200을 지수마다 카드 하나씩
  보인다. 과거 사례와 견주어 볼 출발점이지 경제환경 검색의 입력이 아니다.

  - 한 줄에 이름·값·직전 거래일 대비 변화를 둔다. 값의 색은 오르내림이다(상승 빨강·하락 파랑, 좋고 나쁨이 아니다).
  - 최신값 응답에는 직전 값이 없어 최근 흐름(`GET /markets/{code}/series`)의 마지막 두 거래일로 견준다.
  - 출처와 기준일은 카드 아래 오른쪽에 한 줄로 밝힌다(FR-015). 응답이 준 이름을 그대로 쓴다.
  - 받지 못한 지수는 빼고, 하나도 받지 못하면 한 문장만 남긴다. 투자 대상 고르기는 막히지 않는다.
*/

const CODES: { code: MarketSeriesCode; label: string }[] = [
  { code: 'KOSPI', label: 'KOSPI' },
  { code: 'VKOSPI200', label: 'VKOSPI 200' },
]

type Reading = {
  code: MarketSeriesCode
  label: string
  value: number
  change: number | null
  lastDate: string
  provider: string
}

type State = { status: 'loading' } | { status: 'ready'; readings: Reading[] } | { status: 'unavailable' }

function toReading(label: string, code: MarketSeriesCode, points: { date: string; value: number }[], provider: string): Reading | null {
  const last = points[points.length - 1]
  if (!last) return null
  const previous = points[points.length - 2]
  const change = previous && previous.value !== 0 ? ((last.value - previous.value) / previous.value) * 100 : null
  return { code, label, value: last.value, change, lastDate: last.date, provider }
}

function directionOf(change: number | null) {
  if (change === null) return undefined
  return change > 0 ? 'up' : change < 0 ? 'down' : undefined
}

function formatChange(change: number) {
  const sign = change > 0 ? '+' : change < 0 ? '−' : ''
  return `${sign}${Math.abs(change).toFixed(2)}%`
}

export function MarketNow() {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let active = true
    void Promise.allSettled(CODES.map((item) => fetchMarketSeries(item.code))).then((results) => {
      if (!active) return
      const readings = results.flatMap((result, index) => {
        if (result.status !== 'fulfilled') return []
        const reading = toReading(CODES[index].label, CODES[index].code, result.value.points, result.value.source.provider)
        return reading ? [reading] : []
      })
      setState(readings.length > 0 ? { status: 'ready', readings } : { status: 'unavailable' })
    })
    return () => {
      active = false
    }
  }, [])

  if (state.status === 'loading') return <p className={styles.note} role="status">국내 시장 자료를 불러오는 중입니다.</p>
  if (state.status === 'unavailable') return <p className={styles.note}>국내 시장 참고 정보를 지금은 불러올 수 없습니다.</p>

  const providers = [...new Set(state.readings.map((reading) => reading.provider))].join(', ')
  const lastDate = state.readings.map((reading) => reading.lastDate).sort().at(-1) ?? ''

  return (
    <div className={styles.market}>
      <ul className={styles.cards}>
        {state.readings.map((reading) => {
          const direction = directionOf(reading.change)
          return (
            <li key={reading.code} className={styles.card}>
              <span className={styles.name}>{reading.label}</span>
              <span className={styles.reading} data-direction={direction}>
                <strong>{reading.value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}</strong>
                {reading.change === null ? null : <em>{formatChange(reading.change)}</em>}
              </span>
            </li>
          )
        })}
      </ul>
      <p className={styles.source}>
        {lastDate.replaceAll('-', '.')} 기준 · 출처 {providers}
      </p>
    </div>
  )
}
