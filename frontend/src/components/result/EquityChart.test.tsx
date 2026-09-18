import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { EquityChart, type EquityChartSeries } from '@/components/result/EquityChart'
import { formatAxisAmount } from '@/components/result/formatResult'
import { makeEquitySeries } from '@/test/resultFixtures'
import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * jsdom은 요소의 크기를 재지 않아 `ParentSize`가 언제나 0을 보고하고, 폭이 0이면 차트는 아무것도
 * 그리지 않는다. 그려진 것을 확인해야 하므로 여기서만 크기를 정해 준다.
 */
vi.mock('@visx/responsive', () => ({
  ParentSize: ({
    children,
  }: {
    children: (size: { width: number; height: number }) => ReactNode
  }) => children({ width: 840, height: 380 }),
}))

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
function seriesPath(container: HTMLElement, which: 'base' | 'target'): SVGPathElement {
  const stroke = which === 'base' ? '--chart-line-primary' : '--chart-line-secondary'
  const path = container.querySelector<SVGPathElement>(`path[stroke="var(${stroke})"]`)
  if (!path) throw new Error(`${which} 곡선을 찾지 못했다`)
  return path
}

/** 경로에 찍힌 점의 수. `M`과 `L` 하나가 점 하나다 */
function pointCount(path: SVGPathElement): number {
  return (path.getAttribute('d') ?? '').match(/[ML]/g)?.length ?? 0
}

describe('EquityChart', () => {
  it('비교 대상이 있으면 파선 곡선과 이름표 둘을 그린다', () => {
    const { container } = render(
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" target={target} />,
    )

    expect(seriesPath(container, 'base').getAttribute('stroke-dasharray')).toBeNull()
    expect(seriesPath(container, 'target').getAttribute('stroke-dasharray')).toBe('7,5')
    expect(screen.getByText('물결 고르기')).toBeTruthy()
    expect(screen.getByText('모래 고르기')).toBeTruthy()
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

    // 그림 영역의 바탕 사각형. 축 여백을 뺀 실제 그리기 폭이다.
    const plot = container.querySelectorAll('rect[fill="transparent"]')[1]
    const innerWidth = Number(plot.getAttribute('width'))
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
      <EquityChart altNumbers={altNumbers} base={base} initialCash="10000000.00" target={target} />,
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
