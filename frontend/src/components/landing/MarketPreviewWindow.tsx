import { useEffect, useMemo, useRef, useState } from 'react'
import { useReducedMotion } from 'motion/react'
import { fetchMarketSeries } from '@/api/market'
import type { Indicator } from '@/types/market'
import type { MarketSeriesCode } from '@/types/marketApi'
import styles from '@/pages/LandingPage.module.css'

/*
  첫 화면 오른쪽 창. 국내 시장 지표 칸을 눌러 고르면 그 지표의 차트가 끊김 없이 흘러간다.
  지표는 저절로 넘어가지 않는다. 사용자가 보던 지표가 멋대로 바뀌면 읽던 흐름이 끊긴다.

  **실제 값이 먼저다.** 백엔드가 모아 둔 최근 거래일 종가(`GET /markets/{code}/series`)를 받으면 그 흐름을
  왼쪽부터 그려 나간 뒤 멈추고, 창에 원천과 기준일을 밝힌다. 아직 모으지 못한 지표(503)만 첫 화면이 받은
  지표 값(없으면 예시 값)을 출발점으로 둘레를 오가는 흐름을 만들어 그리고, 창에 '움직임 예시'를 밝힌다.
  어느 쪽이든 'LIVE'라고 쓰지 않는다. 틱 단위 시세는 받지 않는다.

  **예시 흐름을 부드럽게 그리는 방법.** 값은 TICK_MS마다 하나씩 생기는 점(키프레임)이고, 화면은 그 점들을
  곡선으로 이은 값을 매 프레임 같은 x 위치에서 읽어 그린다. 시간이 흐르면 읽는 자리가 조금씩
  옮겨 가므로 선이 한 칸씩 튀지 않고 흘러가며, 끝점은 오른쪽 끝에 머문 채 값만 바뀐다. 세로 범위도
  한 번에 바꾸지 않고 따라가게 한다. 매 프레임 React를 다시 그리지 않고 SVG를 직접 고친다.
*/
const MARKETS = [
  { code: 'KOSPI', label: 'KOSPI', volatility: 0.0011 },
  { code: 'KOSDAQ', label: 'KOSDAQ', volatility: 0.0015 },
  { code: 'USD_KRW', label: '원/달러', volatility: 0.0005 },
  // 국내판 VIX(변동성 지수)다.
  { code: 'VKOSPI200', label: 'VKOSPI 200', volatility: 0.004 },
] as const

/** 쌓아 두는 점 수. 화면에는 이 중 VISIBLE 걸음만큼이 보인다. */
const POINTS = 64
const VISIBLE = 60
/** 화면에서 읽는 자리 수. 점보다 촘촘해야 곡선이 매끄럽다. */
const SAMPLES = 180
const TICK_MS = 650
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

type Series = { base: number; values: number[]; drift: number; random: () => number }

/**
 * 한 걸음. 앞 걸음의 기울기를 조금 이어받아(관성) 뾰족하게 꺾이지 않고, 출발값에서 멀어질수록
 * 되돌아오게 해 화면 밖으로 흘러가지 않는다.
 */
function nextValue(series: Series, volatility: number) {
  const previous = series.values[series.values.length - 1]
  const shock = (series.random() * 2 - 1) * volatility
  series.drift = series.drift * 0.74 + shock
  const pull = ((series.base - previous) / series.base) * 0.035
  return previous * (1 + series.drift + pull)
}

/** 끝점이 출발값에 닿는 첫 흐름을 만든다. 창을 열었을 때 보이는 마지막 값이 받은 값과 같다. */
function createSeries(base: number, volatility: number, seed: number): Series {
  const random = seededRandom(seed)
  const series: Series = { base, values: [base * (1 - volatility * 12 * (random() - 0.4))], drift: 0, random }
  while (series.values.length < POINTS) series.values.push(nextValue(series, volatility))
  const scale = base / series.values[series.values.length - 2]
  series.values = series.values.map((value) => value * scale)
  return series
}

/** 점들 사이를 부드럽게 잇는 값(Catmull-Rom). `time`은 점 번호 단위다. */
function sampleAt(values: number[], time: number) {
  const index = Math.min(values.length - 2, Math.max(0, Math.floor(time)))
  const t = Math.min(1, Math.max(0, time - index))
  const p0 = values[Math.max(0, index - 1)]
  const p1 = values[index]
  const p2 = values[index + 1]
  const p3 = values[Math.min(values.length - 1, index + 2)]
  return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t)
}

type Market = (typeof MARKETS)[number] & { base: number; decimals: number }

function toMarkets(indicators: Indicator[]): Market[] {
  return MARKETS.map((market) => {
    const indicator = indicators.find((item) => item.code === market.code)
    return {
      ...market,
      base: indicator && indicator.value > 0 ? indicator.value : 1,
      decimals: indicator?.decimals ?? 2,
    }
  })
}

function format(value: number, decimals: number) {
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(value)
}

function formatChange(change: number) {
  return `${change > 0 ? '+' : ''}${change.toFixed(2)}%`
}

function directionOf(change: number) {
  return change > 0.005 ? 'up' : change < -0.005 ? 'down' : undefined
}

/** 창에 보이는 구간의 처음과 끝. 끝은 지금 시각의 곡선 값이다. */
function windowEnds(series: Series, phase: number) {
  const now = POINTS - 2 + phase
  return { first: sampleAt(series.values, now - VISIBLE), last: sampleAt(series.values, now) }
}

/** 백엔드가 모아 둔 실제 최근 흐름. 없으면 그 지표는 예시 흐름으로 그린다. */
type RealSeries = { values: number[]; lastDate: string; provider: string; stale: boolean }

/** 실제 흐름을 왼쪽부터 끝까지 그려 나가는 시간. 다 그리면 멈춘다. */
const REVEAL_MS = 1400

function easeOut(t: number) {
  return 1 - (1 - t) ** 3
}

export function MarketPreviewWindow({ indicators }: { indicators: Indicator[] }) {
  const reducedMotion = useReducedMotion()
  const markets = useMemo(() => toMarkets(indicators), [indicators])
  const [active, setActive] = useState(0)
  const [real, setReal] = useState<Partial<Record<MarketSeriesCode, RealSeries>>>({})
  const store = useRef<Record<string, Series>>({})

  // 출발값이 바뀌면(최신 값을 받으면) 그 지표의 예시 흐름을 새로 만든다.
  for (const market of markets) {
    const current = store.current[market.code]
    if (!current || current.base !== market.base) {
      store.current[market.code] = createSeries(market.base, market.volatility, Math.round(market.base * 100) + market.code.length)
    }
  }

  // 한 지표가 실패해도(아직 못 모아 503 등) 나머지는 실제 값으로 그린다.
  useEffect(() => {
    let alive = true
    void Promise.allSettled(MARKETS.map((market) => fetchMarketSeries(market.code))).then((results) => {
      if (!alive) return
      const next: Partial<Record<MarketSeriesCode, RealSeries>> = {}
      results.forEach((result, index) => {
        if (result.status !== 'fulfilled') return
        const points = result.value.points
        next[MARKETS[index].code] = {
          values: points.map((point) => point.value),
          lastDate: points[points.length - 1].date,
          provider: result.value.source.provider,
          stale: result.value.status === 'stale',
        }
      })
      setReal(next)
    })
    return () => {
      alive = false
    }
  }, [])

  const line = useRef<SVGPathElement>(null)
  const area = useRef<SVGPathElement>(null)
  const bars = useRef<SVGGElement>(null)
  const lastLine = useRef<SVGLineElement>(null)
  const point = useRef<SVGCircleElement>(null)
  const pulse = useRef<SVGCircleElement>(null)
  const tag = useRef<SVGGElement>(null)
  const tagText = useRef<SVGTextElement>(null)
  const titleValue = useRef<HTMLSpanElement>(null)
  const tickerValue = useRef<HTMLElement>(null)
  const tickerChange = useRef<HTMLElement>(null)
  const domain = useRef<{ low: number; high: number } | null>(null)
  const activeReal = real[markets[active].code]

  useEffect(() => {
    const market = markets[active]
    // 다른 지표로 바꾸면 세로 범위를 새로 잡는다. 이어서 따라가면 선이 화면 밖에서 날아든다.
    domain.current = null
    let frame = 0

    /**
     * 곡선 위 시각 t0~tEnd를 그린다. x는 t0~t0+span을 창 폭에 늘어놓은 자리다. 실제 흐름은 t0=0에 고정하고 tEnd가
     * 자라며 그려지고, 예시 흐름은 창이 시간을 따라 옮겨 간다. `fixed`면 세로 범위를 전체 값으로 미리 잡는다.
     */
    const render = (values: number[], t0: number, span: number, tEnd: number, fixed: boolean) => {
      const count = Math.max(2, Math.round(SAMPLES * ((tEnd - t0) / span)))
      const times: number[] = []
      const samples: number[] = []
      for (let j = 0; j < count; j += 1) {
        const time = t0 + (j / (count - 1)) * (tEnd - t0)
        times.push(time)
        samples.push(sampleAt(values, time))
      }
      const first = sampleAt(values, t0)
      const last = samples[samples.length - 1]

      // 세로 범위는 곧바로 바꾸지 않고 따라간다. 범위가 튀면 선 전체가 움찔한다.
      const basis = fixed ? values : samples
      const minimum = Math.min(...basis)
      const maximum = Math.max(...basis)
      const pad = (maximum - minimum) * 0.14 || last * 0.001
      const target = { low: minimum - pad, high: maximum + pad }
      const current = domain.current ?? target
      const follow = reducedMotion || fixed ? 1 : 0.08
      domain.current = { low: current.low + (target.low - current.low) * follow, high: current.high + (target.high - current.high) * follow }
      const { low, high } = domain.current

      const x = (time: number) => plot.left + ((time - t0) / span) * (plot.right - plot.left)
      const y = (value: number) => plot.bottom - ((value - low) / (high - low)) * (plot.bottom - plot.top)
      let d = ''
      for (let j = 0; j < count; j += 1) d += `${j === 0 ? 'M' : 'L'}${x(times[j]).toFixed(2)} ${y(samples[j]).toFixed(2)}`
      const tipX = x(tEnd).toFixed(2)
      line.current?.setAttribute('d', d)
      area.current?.setAttribute('d', `${d} L${tipX} ${plot.bottom} L${plot.left} ${plot.bottom} Z`)

      // 막대는 점 사이 하루 움직임의 크기다. 선과 함께 옮겨 가고, 아직 그리지 않은 자리는 숨긴다.
      const group = bars.current
      if (group) {
        const moves: number[] = []
        for (let k = 1; k < values.length; k += 1) moves.push(Math.abs(values[k] - values[k - 1]))
        const largest = Math.max(...moves) || 1
        const rects = group.children
        for (let k = 1; k <= rects.length; k += 1) {
          const rect = rects[k - 1] as SVGRectElement
          if (k >= values.length || k < t0 || k > tEnd) {
            rect.style.opacity = '0'
            continue
          }
          const height = 3 + (moves[k - 1] / largest) * (plot.barBottom - plot.barTop - 3)
          rect.setAttribute('x', (x(k) - 1.6).toFixed(2))
          rect.setAttribute('y', (plot.barBottom - height).toFixed(2))
          rect.setAttribute('height', height.toFixed(2))
          rect.setAttribute('data-direction', values[k] >= values[k - 1] ? 'up' : 'down')
          rect.style.opacity = ''
        }
      }

      const lastY = y(last).toFixed(2)
      lastLine.current?.setAttribute('y1', lastY)
      lastLine.current?.setAttribute('y2', lastY)
      point.current?.setAttribute('cx', tipX)
      point.current?.setAttribute('cy', lastY)
      pulse.current?.setAttribute('cx', tipX)
      pulse.current?.setAttribute('cy', lastY)
      tag.current?.setAttribute('transform', `translate(${plot.right + 6} ${(y(last) - 9).toFixed(2)})`)
      const text = format(last, market.decimals)
      if (tagText.current) tagText.current.textContent = text
      if (titleValue.current) titleValue.current.textContent = text
      if (tickerValue.current) tickerValue.current.textContent = text
      const change = ((last - first) / first) * 100
      const direction = directionOf(change)
      if (tickerChange.current) tickerChange.current.textContent = formatChange(change)
      for (const element of [tickerChange.current, titleValue.current]) {
        if (!element) continue
        if (direction) element.setAttribute('data-direction', direction)
        else element.removeAttribute('data-direction')
      }
    }

    // 실제 흐름: 왼쪽부터 끝까지 그려 나가고 멈춘다. 받은 값 밖으로 움직임을 지어내지 않는다.
    if (activeReal) {
      const values = activeReal.values
      const span = values.length - 1
      if (reducedMotion) {
        render(values, 0, span, span, true)
        return
      }
      const startedAt = performance.now()
      const reveal = (time: number) => {
        const progress = Math.min(1, Math.max(0, (time - startedAt) / REVEAL_MS))
        render(values, 0, span, Math.max(0.05, easeOut(progress) * span), true)
        if (progress < 1) frame = requestAnimationFrame(reveal)
      }
      frame = requestAnimationFrame(reveal)
      return () => cancelAnimationFrame(frame)
    }

    // 예시 흐름: 점이 쌓이며 창이 시간을 따라 옮겨 간다.
    const series = store.current[market.code]
    let tickStartedAt = performance.now()
    const renderExample = (phase: number) => {
      const now = POINTS - 2 + phase
      render(series.values, now - VISIBLE, VISIBLE, now, false)
    }
    if (reducedMotion) {
      renderExample(0)
      return
    }
    const loop = (time: number) => {
      if (!document.hidden) {
        let phase = (time - tickStartedAt) / TICK_MS
        // 한 걸음이 지나면 새 점을 붙이고 가장 오래된 점을 뺀다. 읽는 시각이 같이 한 칸 당겨져 화면은 이어진다.
        while (phase >= 1) {
          series.values = [...series.values.slice(1), nextValue(series, market.volatility)]
          tickStartedAt += TICK_MS
          phase -= 1
        }
        renderExample(phase)
      } else {
        tickStartedAt = time
      }
      frame = requestAnimationFrame(loop)
    }
    frame = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(frame)
  }, [active, activeReal, markets, reducedMotion])

  const market = markets[active]
  /** 칸과 제목에 처음 보일 값. 실제 흐름은 받은 구간의 처음·끝, 예시는 창의 처음·끝이다. */
  const endsOf = (code: MarketSeriesCode) => {
    const series = real[code]
    if (series) return { first: series.values[0], last: series.values[series.values.length - 1] }
    return windowEnds(store.current[code], 0)
  }
  const opening = endsOf(market.code)

  return (
    <div className={styles.previewWindow} role="group" aria-label={activeReal ? '국내 시장 지표 최근 흐름' : '국내 시장 지표 움직임 예시'}>
      <div className={styles.previewBar}>
        <span className={styles.previewDots} aria-hidden="true"><i /><i /><i /></span>
        <span className={styles.previewAddress} aria-hidden="true">thorp · <b>시장 흐름</b></span>
        {/* 실제 값이면 원천과 기준일을, 아니면 만든 흐름임을 밝힌다. */}
        <span className={styles.previewExample}>
          {activeReal ? `${activeReal.provider} · ${activeReal.lastDate} 기준${activeReal.stale ? ' · 갱신 지연' : ''}` : '움직임 예시'}
        </span>
      </div>

      <div className={styles.previewTickers}>
        {markets.map((item, index) => {
          // 고른 지표의 값은 그리는 흐름이 직접 바꾼다. 나머지는 구간의 끝값이다.
          const ends = endsOf(item.code)
          const change = ((ends.last - ends.first) / ends.first) * 100
          const isActive = index === active
          return (
            <button key={item.code} type="button" aria-pressed={isActive} onClick={() => setActive(index)}>
              <span>{item.label}</span>
              <strong aria-hidden="true" ref={isActive ? tickerValue : undefined}>{format(ends.last, item.decimals)}</strong>
              <em aria-hidden="true" ref={isActive ? tickerChange : undefined} data-direction={directionOf(change)}>
                {formatChange(change)}
              </em>
            </button>
          )
        })}
      </div>

      <p className={styles.previewChartTitle} aria-hidden="true">
        {market.label} <b>{activeReal ? `최근 ${activeReal.values.length}거래일` : '최근 흐름'}</b>
        <span ref={titleValue}>{format(opening.last, market.decimals)}</span>
      </p>
      <svg className={styles.previewChart} viewBox={`0 0 ${plot.width} ${plot.height}`} aria-hidden="true">
        <defs>
          <clipPath id="market-preview-plot">
            <rect x={plot.left} y={0} width={plot.right - plot.left} height={plot.height} />
          </clipPath>
        </defs>
        {[0.25, 0.5, 0.75].map((ratio) => {
          const lineY = plot.top + ratio * (plot.bottom - plot.top)
          return <line key={ratio} className={styles.previewGrid} x1={plot.left} x2={plot.right} y1={lineY} y2={lineY} />
        })}
        <g ref={bars} clipPath="url(#market-preview-plot)">
          {Array.from({ length: POINTS - 1 }, (_, index) => (
            <rect key={index} className={styles.previewVolume} width={3.2} x={-10} y={plot.barBottom} height={0} />
          ))}
        </g>
        <g clipPath="url(#market-preview-plot)">
          <path ref={area} className={styles.previewArea} />
          <path ref={line} className={styles.previewLine} />
        </g>
        <line ref={lastLine} className={styles.previewLastLine} x1={plot.left} x2={plot.right + 6} y1={plot.bottom} y2={plot.bottom} />
        <circle ref={pulse} className={styles.previewPulse} cx={plot.right} cy={plot.bottom} r={6} />
        <circle ref={point} className={styles.previewPoint} cx={plot.right} cy={plot.bottom} r={3.2} />
        <g ref={tag} transform={`translate(${plot.right + 6} ${plot.bottom - 9})`}>
          <rect className={styles.previewTag} width={plot.width - plot.right - 6} height={18} rx={4} />
          <text ref={tagText} className={styles.previewTagText} x={(plot.width - plot.right - 6) / 2} y={12.5} textAnchor="middle">
            {format(opening.last, market.decimals)}
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
