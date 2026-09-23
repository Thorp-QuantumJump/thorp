import { useEffect, useMemo, useRef, useState } from 'react'
import { useReducedMotion } from 'motion/react'
import type { Indicator } from '@/types/market'
import styles from '@/pages/LandingPage.module.css'

/*
  첫 화면 오른쪽 창. 국내 시장 지표를 탭으로 바꿔 가며 차트가 계속 그려지는 모습을 보인다.

  **움직임은 만든 것이다.** 틱 단위 시세를 받지 않으므로, 첫 화면이 받은 지표 값(없으면 예시 값)을
  출발점으로 삼아 그 둘레를 오가는 흐름을 그린다. 그래서 창에 '움직임 예시'를 밝히고 'LIVE'라고
  쓰지 않는다. 실제 값과 출처는 시장 흐름 화면이 보인다.
*/
const MARKETS = [
  { code: 'KOSPI', label: 'KOSPI', volatility: 0.0016 },
  { code: 'KOSDAQ', label: 'KOSDAQ', volatility: 0.0022 },
  { code: 'USD_KRW', label: '원/달러', volatility: 0.0007 },
  // 국내판 VIX(변동성 지수)다.
  { code: 'VKOSPI200', label: 'VKOSPI 200', volatility: 0.006 },
] as const

const POINTS = 72
const TICK_MS = 900
const ROTATE_MS = 7000
const plot = { width: 400, height: 190, left: 8, right: 336, top: 14, bottom: 146, barTop: 156, barBottom: 184 }

/** 같은 출발값이면 같은 흐름이 나오도록 씨앗을 받는 난수다(렌더마다 모양이 튀지 않게). */
function seededRandom(seed: number) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 한 걸음. 출발값에서 멀어질수록 조금씩 되돌아오게 해 화면 밖으로 흘러가지 않는다. */
function step(previous: number, base: number, volatility: number, random: () => number) {
  const shock = (random() * 2 - 1) * volatility * 1.7
  const pull = ((base - previous) / base) * 0.05
  return previous * (1 + shock + pull)
}

/** 끝점이 출발값에 닿는 첫 흐름을 만든다. 창을 열었을 때 보이는 마지막 값이 받은 값과 같다. */
function initialSeries(base: number, volatility: number, random: () => number) {
  const values: number[] = []
  let value = base * (1 - volatility * 10 * (random() - 0.35))
  for (let index = 0; index < POINTS; index += 1) {
    value = step(value, base, volatility, random)
    values.push(value)
  }
  const scale = base / values[values.length - 1]
  return values.map((item) => item * scale)
}

type Market = (typeof MARKETS)[number] & { base: number; decimals: number; unit: string }

function toMarkets(indicators: Indicator[]): Market[] {
  return MARKETS.map((market) => {
    const indicator = indicators.find((item) => item.code === market.code)
    return {
      ...market,
      base: indicator && indicator.value > 0 ? indicator.value : 1,
      decimals: indicator?.decimals ?? 2,
      unit: indicator?.unit ?? '',
    }
  })
}

function format(value: number, decimals: number) {
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(value)
}

export function MarketPreviewWindow({ indicators }: { indicators: Indicator[] }) {
  const reducedMotion = useReducedMotion()
  const markets = useMemo(() => toMarkets(indicators), [indicators])
  const [active, setActive] = useState(0)
  const [pinned, setPinned] = useState(false)
  const [, setTick] = useState(0)
  const series = useRef<Record<string, { base: number; values: number[]; random: () => number }>>({})

  // 출발값이 바뀌면(최신 값을 받으면) 그 지표의 흐름을 새로 만든다.
  for (const market of markets) {
    const current = series.current[market.code]
    if (!current || current.base !== market.base) {
      const random = seededRandom(Math.round(market.base * 100) + market.code.length)
      series.current[market.code] = { base: market.base, values: initialSeries(market.base, market.volatility, random), random }
    }
  }

  useEffect(() => {
    if (reducedMotion) return
    const timer = window.setInterval(() => {
      if (document.hidden) return
      const market = markets[active]
      const entry = series.current[market.code]
      const last = entry.values[entry.values.length - 1]
      entry.values = [...entry.values.slice(1), step(last, entry.base, market.volatility, entry.random)]
      setTick((value) => value + 1)
    }, TICK_MS)
    return () => window.clearInterval(timer)
  }, [active, markets, reducedMotion])

  useEffect(() => {
    if (reducedMotion || pinned) return
    const timer = window.setInterval(() => {
      if (!document.hidden) setActive((value) => (value + 1) % markets.length)
    }, ROTATE_MS)
    return () => window.clearInterval(timer)
  }, [markets.length, pinned, reducedMotion])

  const market = markets[active]
  const values = series.current[market.code].values
  const first = values[0]
  const last = values[values.length - 1]
  const change = ((last - first) / first) * 100
  const direction = change > 0.005 ? 'up' : change < -0.005 ? 'down' : undefined

  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  const pad = (maximum - minimum) * 0.12 || last * 0.001
  const low = minimum - pad
  const high = maximum + pad
  const x = (index: number) => plot.left + (index / (POINTS - 1)) * (plot.right - plot.left)
  const y = (value: number) => plot.bottom - ((value - low) / (high - low)) * (plot.bottom - plot.top)
  const line = values.map((value, index) => `${index === 0 ? 'M' : 'L'}${x(index).toFixed(1)} ${y(value).toFixed(1)}`).join(' ')
  const area = `${line} L${plot.right} ${plot.bottom} L${plot.left} ${plot.bottom} Z`
  const moves = values.slice(1).map((value, index) => Math.abs(value - values[index]))
  const largestMove = Math.max(...moves) || 1

  const quotes = markets.map((item) => {
    const itemValues = series.current[item.code].values
    const itemLast = itemValues[itemValues.length - 1]
    const itemChange = ((itemLast - itemValues[0]) / itemValues[0]) * 100
    return { ...item, last: itemLast, change: itemChange }
  })

  return (
    <div className={styles.previewWindow} role="group" aria-label="국내 시장 지표 움직임 예시">
      <div className={styles.previewBar}>
        <span className={styles.previewDots} aria-hidden="true"><i /><i /><i /></span>
        <span className={styles.previewAddress} aria-hidden="true">thorp · <b>시장 흐름</b></span>
        <span className={styles.previewExample}>움직임 예시</span>
      </div>

      <div className={styles.previewTickers}>
        {quotes.map((item, index) => {
          const itemDirection = item.change > 0.005 ? 'up' : item.change < -0.005 ? 'down' : undefined
          return (
            <button
              key={item.code}
              type="button"
              aria-pressed={index === active}
              onClick={() => {
                setActive(index)
                setPinned(true)
              }}
            >
              <span>{item.label}</span>
              <strong aria-hidden="true">{format(item.last, item.decimals)}</strong>
              <em aria-hidden="true" data-direction={itemDirection}>
                {item.change > 0 ? '+' : ''}
                {item.change.toFixed(2)}%
              </em>
            </button>
          )
        })}
      </div>

      <p className={styles.previewChartTitle} aria-hidden="true">
        {market.label} <b>최근 흐름</b>
        <span data-direction={direction}>{format(last, market.decimals)}</span>
      </p>
      <svg className={styles.previewChart} viewBox={`0 0 ${plot.width} ${plot.height}`} aria-hidden="true">
        {[0.25, 0.5, 0.75].map((ratio) => {
          const lineY = plot.top + ratio * (plot.bottom - plot.top)
          return <line key={ratio} className={styles.previewGrid} x1={plot.left} x2={plot.right} y1={lineY} y2={lineY} />
        })}
        {moves.map((move, index) => {
          const barHeight = 3 + (move / largestMove) * (plot.barBottom - plot.barTop - 3)
          const rising = values[index + 1] >= values[index]
          return (
            <rect
              key={index}
              className={styles.previewVolume}
              data-direction={rising ? 'up' : 'down'}
              x={x(index + 1) - 1.6}
              y={plot.barBottom - barHeight}
              width={3.2}
              height={barHeight}
            />
          )
        })}
        <path className={styles.previewArea} d={area} />
        <path className={styles.previewLine} d={line} />
        <line className={styles.previewLastLine} x1={plot.left} x2={plot.right + 6} y1={y(last)} y2={y(last)} />
        <circle className={styles.previewPulse} cx={x(POINTS - 1)} cy={y(last)} r={6} />
        <circle className={styles.previewPoint} cx={x(POINTS - 1)} cy={y(last)} r={3.2} />
        <g transform={`translate(${plot.right + 6} ${y(last) - 9})`}>
          <rect className={styles.previewTag} width={plot.width - plot.right - 6} height={18} rx={4} />
          <text className={styles.previewTagText} x={(plot.width - plot.right - 6) / 2} y={12.5} textAnchor="middle">
            {format(last, market.decimals)}
          </text>
        </g>
      </svg>

      <RuleComparison />
    </div>
  )
}

/** 시장 흐름에서 전략 비교로 이어지는 자리. 값은 예시다. */
const EXAMPLE_RULES = [
  { name: '역변동성', net: '+24.6%', drawdown: '−12.1%', cost: '84,200원', selected: true },
  { name: '동일 비중', net: '+13.2%', drawdown: '−18.7%', cost: '61,000원' },
  { name: '최소분산', net: '+9.8%', drawdown: '−9.4%', cost: '72,500원' },
] as const

function RuleComparison() {
  return (
    <table className={styles.previewTable} aria-hidden="true">
      <thead>
        <tr><th>같은 조건의 배분 규칙</th><th>수익</th><th>하락 폭</th><th data-optional="true">거래비용</th></tr>
      </thead>
      <tbody>
        {EXAMPLE_RULES.map((rule) => (
          <tr key={rule.name} data-selected={'selected' in rule ? true : undefined}>
            <td>{rule.name}</td>
            <td data-direction="up">{rule.net}</td>
            <td data-direction="down">{rule.drawdown}</td>
            <td data-optional="true">{rule.cost}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
