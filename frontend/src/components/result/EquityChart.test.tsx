import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EquityChart, type EquityChartSeries } from '@/components/result/EquityChart'
import { formatAxisAmount } from '@/components/result/formatResult'
import { makeEquitySeries } from '@/test/resultFixtures'
import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * jsdom은 요소의 크기를 재지 않아 `ParentSize`가 언제나 0을 보고하고, 폭이 0이면 차트는 아무것도
 * 그리지 않는다. 그려진 것을 확인해야 하므로 여기서만 크기를 정해 준다. 좁은 화면을 볼 때는 테스트
 * 안에서 값을 바꾸고 `afterEach`가 되돌린다.
 */
const WIDE = { width: 840, height: 380 }
const chartSize = vi.hoisted(() => ({ width: 840, height: 380 }))

vi.mock('@visx/responsive', () => ({
  ParentSize: ({
    children,
  }: {
    children: (size: { width: number; height: number }) => ReactNode
  }) => children({ width: chartSize.width, height: chartSize.height }),
}))

afterEach(() => {
  Object.assign(chartSize, WIDE)
  window.innerWidth = 1024
})

const BASE_POINTS = makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 })
const TARGET_POINTS = makeEquitySeries({ points: 129, peakIndex: 30, troughIndex: 70 })

const base: EquityChartSeries = {
  name: '물결 고르기',
  points: BASE_POINTS,
  maxDrawdown: BASE_POINTS[55].drawdown,
}

const target: EquityChartSeries = {
  name: '모래 고르기',
  points: TARGET_POINTS,
  maxDrawdown: TARGET_POINTS[70].drawdown,
}

const altNumbers = {
  netTotalReturn: '0.166652',
  maxDrawdown: '-0.041757',
  totalTradingCost: '17078.49',
}

/** 곡선의 경로. 기준과 비교 대상은 선 색으로 갈린다 */
const STROKES: Record<string, string> = {
  base: '--chart-line-primary',
  target0: '--result-line-secondary',
  target1: '--result-line-tertiary',
  target2: '--result-line-quaternary',
}

function seriesPath(container: HTMLElement, which: string): SVGPathElement {
  const stroke = STROKES[which]
  const path = container.querySelector<SVGPathElement>(`path[stroke="var(${stroke})"]`)
  if (!path) throw new Error(`${which} 곡선을 찾지 못했다`)
  return path
}

/**
 * 등록표 이름과 같은 길이(17자, 띄어쓰기 포함)의 합성 이름. 실제 이름을 쓰지 않는 이유는
 * `resultFixtures.ts` 머리 주석과 같다.
 */
const LONG_BASE_NAME = '가나다 라마바사 아자차카 타파하'
const LONG_TARGET_NAME = '거너더 러머버서 어저처커 터퍼허'

/** 이름표 하나의 줄들과 세로 범위. 줄마다 `y`가 그 줄의 가운데다 */
function labelBlock(container: HTMLElement, dataKey: string) {
  const lines = [
    ...container.querySelectorAll(`[data-testid="series-end-label-${dataKey}"] tspan`),
  ]
  const centers = lines.map((line) => Number(line.getAttribute('y')))
  const lineHeight = centers.length > 1 ? centers[1] - centers[0] : 13
  return {
    texts: lines.map((line) => line.textContent ?? ''),
    top: Math.min(...centers) - lineHeight / 2,
    bottom: Math.max(...centers) + lineHeight / 2,
  }
}

/** 그림 영역의 바탕 사각형. 축 여백을 뺀 실제 그리기 크기다 */
function plotArea(container: HTMLElement) {
  const plot = container.querySelectorAll('rect[fill="transparent"]')[1]
  return { width: Number(plot.getAttribute('width')), height: Number(plot.getAttribute('height')) }
}

/** 경로에 찍힌 점의 수. `M`과 `L` 하나가 점 하나다 */
function pointCount(path: SVGPathElement): number {
  return (path.getAttribute('d') ?? '').match(/[ML]/g)?.length ?? 0
}

describe('EquityChart', () => {
  it('선택한 전략과 KOSPI를 선 모양 안내에 모두 표시하고 기준선을 더 강조한다', () => {
    const { container } = render(
      <EquityChart
        base={base}
        initialCash="10000000.00"
        targets={[target, { ...target, name: '조약돌 고르기' }]}
        reference={{ name: 'KOSPI(참고)', points: [{ date: BASE_POINTS[0].date, value: '10000000' }] }}
      />,
    )

    const key = container.querySelector('[aria-label="그래프 선 구분"]')
    expect(key?.textContent).toContain('물결 고르기')
    expect(key?.textContent).toContain('모래 고르기')
    expect(key?.textContent).toContain('조약돌 고르기')
    expect(key?.textContent).toContain('KOSPI(참고)')
    expect(Number(seriesPath(container, 'base').getAttribute('stroke-width'))).toBeGreaterThan(
      Number(seriesPath(container, 'target0').getAttribute('stroke-width')),
    )
    const paths = [...container.querySelectorAll('path')]
    expect(paths.indexOf(seriesPath(container, 'base'))).toBeGreaterThan(
      paths.indexOf(seriesPath(container, 'target0')),
    )
  })

  it('비교 대상이 있으면 파선 곡선과 이름표 둘을 그린다', () => {
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={base}
        initialCash="10000000.00"
        targets={[target]}
      />,
    )

    expect(seriesPath(container, 'base').getAttribute('stroke-dasharray')).toBeNull()
    expect(seriesPath(container, 'target0').getAttribute('stroke-dasharray')).toBe('7,5')
    expect(screen.getAllByText('물결 고르기').length).toBeGreaterThan(0)
    expect(screen.getAllByText('모래 고르기').length).toBeGreaterThan(0)
  })

  it('비교 대상이 셋이면 파선 모양과 색이 서로 다르고 이름표가 넷이며 비교 대상 음영은 없다', () => {
    const others = ['조약돌 고르기', '해초 고르기'].map((name) => ({ ...target, name }))
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={base}
        initialCash="10000000.00"
        targets={[target, ...others]}
      />,
    )
    const dashes = [0, 1, 2].map((index) =>
      seriesPath(container, 'target' + index).getAttribute('stroke-dasharray'),
    )
    const strokes = [0, 1, 2].map((index) =>
      seriesPath(container, 'target' + index).getAttribute('stroke'),
    )
    expect(new Set(dashes).size).toBe(3)
    expect(new Set(strokes).size).toBe(3)
    for (const name of ['물결 고르기', '모래 고르기', '조약돌 고르기', '해초 고르기'])
      expect(screen.getAllByText(name).length).toBeGreaterThan(0)
    expect(container.querySelector('[data-testid="drawdown-shade-target"]')).toBeNull()
    expect(container.querySelector('[data-testid="drawdown-shade-base"]')).not.toBeNull()
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain(
      '모래 고르기, 조약돌 고르기, 해초 고르기',
    )
  })

  it('넓은 화면에서 긴 이름도 자르지 않고 여러 줄로 나누며 두 이름표가 겹치지 않는다', () => {
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ ...base, name: LONG_BASE_NAME }}
        initialCash="10000000.00"
        targets={[{ ...target, name: LONG_TARGET_NAME }]}
      />,
    )

    const baseLabel = labelBlock(container, 'base')
    const targetLabel = labelBlock(container, 'target0')
    const { height } = plotArea(container)

    for (const [label, name] of [
      [baseLabel, LONG_BASE_NAME],
      [targetLabel, LONG_TARGET_NAME],
    ] as const) {
      expect(label.texts.length).toBeGreaterThan(1)
      expect(label.texts.join(' ')).toBe(name)
      // 오른쪽 여백(88px)에 11px 한글이 일곱 자까지 들어간다.
      for (const text of label.texts) expect(text.replace(/\s/g, '').length).toBeLessThanOrEqual(7)
      expect(label.top).toBeGreaterThanOrEqual(0)
      expect(label.bottom).toBeLessThanOrEqual(height)
    }
    // 두 곡선의 끝 값이 같아 이름표가 같은 자리를 원한다. 밀어낸 뒤에는 겹치지 않아야 한다.
    const [upper, lower] = [baseLabel, targetLabel].sort((left, right) => left.top - right.top)
    expect(upper.bottom).toBeLessThanOrEqual(lower.top)
    expect(container.textContent).not.toContain('…')
  })

  it('모바일에서는 끝 이름표 대신 위쪽 선 목록을 쓰고 그래프 폭을 확보한다', () => {
    window.innerWidth = 390
    Object.assign(chartSize, { width: 350, height: 430 })
    const { container } = render(
      <EquityChart
        base={{ ...base, name: LONG_BASE_NAME }}
        initialCash="10000000.00"
        targets={[{ ...target, name: LONG_TARGET_NAME }]}
      />,
    )

    expect(container.querySelector('[aria-label="그래프 선 구분"]')?.textContent).toContain(LONG_BASE_NAME)
    expect(container.querySelector('[aria-label="그래프 선 구분"]')?.textContent).toContain(LONG_TARGET_NAME)
    expect(container.querySelector('[data-testid="series-end-labels"]')).toBeNull()
    expect(plotArea(container).width).toBeGreaterThan(240)
  })

  it('기준만 있으면 파선 곡선이 없다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    expect(container.querySelector('path[stroke-dasharray]')).toBeNull()
    expect(screen.queryByText('모래 고르기')).toBeNull()
  })

  it('하락 구간에 음영과 점선 경계를 함께 그린다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    const shade = container.querySelector('[data-testid="drawdown-shade-base"] rect')
    const edge = container.querySelector<SVGLineElement>(
      '[data-testid="drawdown-shade-edge-base"]',
    )
    expect(shade).not.toBeNull()
    expect(Number(shade?.getAttribute('width'))).toBeGreaterThan(0)
    expect(edge?.getAttribute('stroke-dasharray')).toBe('5,4')
  })

  it('음영이 고점과 저점 사이에 놓인다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    const innerWidth = plotArea(container).width
    const first = Date.parse(BASE_POINTS[0].date + 'T00:00:00Z')
    const last = Date.parse(BASE_POINTS[BASE_POINTS.length - 1].date + 'T00:00:00Z')
    const at = (date: string) =>
      (innerWidth * (Date.parse(date + 'T00:00:00Z') - first)) / (last - first)

    const shade = container.querySelector('[data-testid="drawdown-shade-base"] rect')
    const left = Number(shade?.getAttribute('x'))
    const width = Number(shade?.getAttribute('width'))
    expect(left).toBeCloseTo(at(BASE_POINTS[40].date), 1)
    expect(left + width).toBeCloseTo(at(BASE_POINTS[55].date), 1)
  })

  it('보고된 최대 하락 폭과 저점이 어긋나면 음영을 그리지 않는다', () => {
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ ...base, maxDrawdown: '-0.010000' }}
        initialCash="10000000.00"
      />,
    )

    expect(container.querySelector('[data-testid="drawdown-shade-base"]')).toBeNull()
    expect(seriesPath(container, 'base')).toBeTruthy()
  })

  it('받은 점을 하나도 줄이지 않고 그린다', () => {
    const points = makeEquitySeries({ points: 1900, peakIndex: 600, troughIndex: 900 })
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ name: '물결 고르기', points, maxDrawdown: points[900].drawdown }}
        initialCash="10000000.00"
      />,
    )

    expect(pointCount(seriesPath(container, 'base'))).toBe(points.length)
  })

  it('자산가치가 없는 날에서 곡선을 끊고 앞뒤를 잇지 않는다', () => {
    const points: ResultTimeseriesPoint[] = BASE_POINTS.map((point, index) =>
      index === 60 ? { ...point, equity: '' } : point,
    )
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ name: '물결 고르기', points, maxDrawdown: BASE_POINTS[55].drawdown }}
        initialCash="10000000.00"
      />,
    )

    const definition = seriesPath(container, 'base').getAttribute('d') ?? ''
    expect(definition.match(/M/g)).toHaveLength(2)
    expect(pointCount(seriesPath(container, 'base'))).toBe(points.length - 1)
  })

  it('그릴 자산가치가 하나도 없으면 빈 축을 그리지 않는다', () => {
    const empty = BASE_POINTS.map((point) => ({ ...point, equity: '' }))
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ name: '물결 고르기', points: empty, maxDrawdown: null }}
        initialCash="10000000.00"
      />,
    )

    expect(container.innerHTML).toBe('')
  })

  it('대안 텍스트에 핵심 숫자 3개와 비교 대상 유무를 담는다', () => {
    const { rerender } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    const alone = screen.getByRole('img').getAttribute('aria-label') ?? ''
    expect(alone).toContain('16.67%')
    expect(alone).toContain('-4.18%')
    expect(alone).toContain('17,078원')
    expect(alone).toContain('비교 대상은 없습니다')

    rerender(
      <EquityChart
        altNumbers={altNumbers}
        base={base}
        initialCash="10000000.00"
        targets={[target]}
      />,
    )
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('모래 고르기')
  })

  it('세로축에 원화 금액 눈금을 넷 이하로 적고 격자선과 같은 높이에 둔다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    const labels = [...container.querySelectorAll('[data-testid="amount-axis"] text')]
    const rowHeights = [...container.querySelectorAll('.visx-rows line')].map((line) =>
      Number(line.getAttribute('y1')),
    )

    expect(labels.length).toBeGreaterThanOrEqual(2)
    expect(labels.length).toBeLessThanOrEqual(4)
    for (const label of labels) {
      expect(label.textContent).toMatch(/^[\d,.]+(만|억)?원$/)
      const height = Number(label.getAttribute('y'))
      expect(rowHeights.some((row) => Math.abs(row - height) < 0.01)).toBe(true)
    }
  })

  it('시작 금액 기준선을 그리고 그 값을 글로도 알린다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    expect(container.querySelector('.chart-grid-highlight-rows line')).not.toBeNull()
    expect(screen.getByText(/시작 금액 10,000,000원/)).toBeTruthy()
  })

  it('기준선 옆에 시작 금액을 적어 그래프 안에서 원금을 읽게 한다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />,
    )

    const label = container.querySelector<SVGTextElement>('[data-testid="initial-cash-label"]')
    expect(label?.textContent).toBe('시작 금액 1,000만원')

    // 기준선과 같은 높이에 놓인다. 선 위로 띄운 만큼만 어긋나야 한다.
    const rule = container.querySelector('.chart-grid-highlight-rows line')
    const ruleY = Number(rule?.getAttribute('y1'))
    expect(Math.abs(Number(label?.getAttribute('y')) - ruleY)).toBeLessThanOrEqual(16)

    // 곡선과 겹쳐도 읽히도록 글자 뒤에 바탕색 외곽선을 두른다.
    expect(label?.getAttribute('paint-order')).toBe('stroke')
  })

  it('시작 금액을 읽을 수 없으면 이름표를 그리지 않는다', () => {
    const { container } = render(<EquityChart altNumbers={altNumbers} base={base} initialCash="" />)

    expect(container.querySelector('[data-testid="initial-cash-label"]')).toBeNull()
    expect(seriesPath(container, 'base')).toBeTruthy()
  })

  it('대안 텍스트에도 시작 금액을 담는다', () => {
    render(<EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" />)

    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('시작 금액은 10,000,000원')
  })
})

/** 눈금 글자를 다시 원화 금액으로 읽는다. `formatAxisAmount`가 적은 만·억 단위를 되돌린다 */
function parseAxisAmount(text: string): number {
  const cleaned = text.replace(/,/g, '')
  if (cleaned.endsWith('억원')) return Number(cleaned.slice(0, -2)) * 100_000_000
  if (cleaned.endsWith('만원')) return Number(cleaned.slice(0, -2)) * 10_000
  return Number(cleaned.slice(0, -1))
}

/** 세로축 눈금마다 적힌 금액과 그 금액이 놓인 높이 */
function axisTicks(container: HTMLElement): { amount: number; y: number }[] {
  return [...container.querySelectorAll('[data-testid="amount-axis"] text')].map((text) => ({
    amount: parseAxisAmount(text.textContent ?? ''),
    y: Number(text.getAttribute('y')),
  }))
}

/**
 * 눈금의 높이가 `project`를 거친 값에 비례하는가. 비례하면 인접한 눈금마다 구한 기울기가 모두 같다.
 * 눈금이 셋은 되어야 뜻이 있다 — 둘뿐이면 어떤 변환을 쓰든 언제나 비례한다.
 */
function slopesOf(
  ticks: { amount: number; y: number }[],
  project: (amount: number) => number,
): number[] {
  const sorted = [...ticks].sort((left, right) => left.amount - right.amount)
  const slopes: number[] = []
  for (let index = 1; index < sorted.length; index += 1) {
    const rise = sorted[index].y - sorted[index - 1].y
    const run = project(sorted[index].amount) - project(sorted[index - 1].amount)
    slopes.push(rise / run)
  }
  return slopes
}

/** 시작 금액에서 끝 금액까지 일정한 비율로 불어나는 계열. 내리막이 없어 음영은 생기지 않는다 */
function makeGrowthSeries(from: number, to: number): ResultTimeseriesPoint[] {
  const dates = BASE_POINTS.map((point) => point.date)
  return dates.map((date, index) => {
    const equity = from * (to / from) ** (index / (dates.length - 1))
    return {
      date,
      equity: equity.toFixed(2),
      cumulativeReturn: (equity / from - 1).toFixed(6),
      drawdown: '0.000000',
    }
  })
}

const GROWTH_POINTS = makeGrowthSeries(10_000_000, 1_000_000_000)
const growth: EquityChartSeries = {
  name: '물결 고르기',
  points: GROWTH_POINTS,
  maxDrawdown: null,
}

describe('EquityChart 세로축 눈금', () => {
  it('차트 제목과 시작 금액 범례를 가까이 두고 읽는 법 펼침은 두지 않는다', () => {
    render(<EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />)

    expect(screen.getByText('자산가치 추이')).not.toBeNull()
    expect(screen.getByText('점선 · 시작 금액 10,000,000원')).not.toBeNull()
    // '차트 읽는 법' 펼침은 뺐다. 음영 구간 설명은 그림 안 도움말(title)과 대안 텍스트가 든다.
    expect(screen.queryByText('차트 읽는 법')).toBeNull()
  })

  it('처음에는 로그 눈금으로 그려 금액의 상용로그에 높이를 비례시킨다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />,
    )

    const ticks = axisTicks(container)
    expect(ticks.length).toBeGreaterThanOrEqual(3)

    const logSlopes = slopesOf(ticks, Math.log10)
    for (const slope of logSlopes) expect(slope).toBeCloseTo(logSlopes[0], 6)

    // 같은 눈금이 금액 자체에는 비례하지 않는다. 비례한다면 로그로 놓인 것이 아니다.
    const linearSlopes = slopesOf(ticks, (amount) => amount)
    expect(Math.abs(linearSlopes[linearSlopes.length - 1] / linearSlopes[0])).toBeLessThan(0.5)
  })

  it('선형으로 바꾸면 금액 자체에 높이를 비례시킨다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />,
    )

    fireEvent.click(screen.getByRole('button', { name: '선형' }))

    const ticks = axisTicks(container)
    expect(ticks.length).toBeGreaterThanOrEqual(3)

    const linearSlopes = slopesOf(ticks, (amount) => amount)
    for (const slope of linearSlopes) expect(slope).toBeCloseTo(linearSlopes[0], 6)
  })

  it('고른 눈금을 단추의 눌림 상태로 알린다', () => {
    render(<EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />)

    const linear = screen.getByRole('button', { name: '선형' })
    const log = screen.getByRole('button', { name: '로그' })
    expect(log.getAttribute('aria-pressed')).toBe('true')
    expect(linear.getAttribute('aria-pressed')).toBe('false')

    fireEvent.click(linear)
    expect(linear.getAttribute('aria-pressed')).toBe('true')
    expect(log.getAttribute('aria-pressed')).toBe('false')
  })

  it('어느 눈금이든 글자는 원화 금액으로 적는다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />,
    )

    const labels = () =>
      [...container.querySelectorAll('[data-testid="amount-axis"] text')].map(
        (text) => text.textContent ?? '',
      )

    for (const label of labels()) expect(label).toMatch(/^[\d,.]+(만|억)?원$/)
    fireEvent.click(screen.getByRole('button', { name: '선형' }))
    for (const label of labels()) expect(label).toMatch(/^[\d,.]+(만|억)?원$/)
  })

  it('대안 텍스트에 지금 보는 눈금 종류를 담는다', () => {
    render(<EquityChart altNumbers={altNumbers} base={growth} initialCash="10000000.00" />)

    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('눈금은 로그')
    fireEvent.click(screen.getByRole('button', { name: '선형' }))
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('눈금은 선형')
  })

  it('로그 눈금에서 0원 이하인 날은 그리지 않고 곡선을 끊는다', () => {
    const points = GROWTH_POINTS.map((point, index) =>
      index === 60 ? { ...point, equity: '0.00' } : point,
    )
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ name: '물결 고르기', points, maxDrawdown: null }}
        initialCash="10000000.00"
      />,
    )

    const definition = seriesPath(container, 'base').getAttribute('d') ?? ''
    expect(definition.match(/M/g)).toHaveLength(2)
    expect(pointCount(seriesPath(container, 'base'))).toBe(points.length - 1)
  })

  it('선형 눈금에서는 0원인 날도 그대로 그린다', () => {
    const points = GROWTH_POINTS.map((point, index) =>
      index === 60 ? { ...point, equity: '0.00' } : point,
    )
    const { container } = render(
      <EquityChart
        altNumbers={altNumbers}
        base={{ name: '물결 고르기', points, maxDrawdown: null }}
        initialCash="10000000.00"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '선형' }))
    expect(pointCount(seriesPath(container, 'base'))).toBe(points.length)
  })
})

describe('formatAxisAmount', () => {
  it.each([
    [10_500_000, '1,050만원'],
    [125_000_000, '1.25억원'],
    [9_500, '9,500원'],
    [Number.NaN, ''],
  ])('%d → "%s"', (value, text) => {
    expect(formatAxisAmount(value)).toBe(text)
  })
})
