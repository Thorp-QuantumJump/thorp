import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { isDrawable } from './diagramShape'
import { HypotheticalDiagram } from './HypotheticalDiagram'

/** 013 T134. 그림은 보조이고, 그릴 수 없으면 설명 문장만 보이는지 본다(FR-026, quickstart 9-1). */
const base = {
  title: '가상의 흐름',
  unit: 'm',
  categories: ['1일', '2일', '3일'],
  description: '둘 다 같은 값에서 끝난다.',
}

describe('HypotheticalDiagram', () => {
  it.each(['line', 'bar'] as const)('%s 그림은 aria-hidden이고 가상의 예 표시와 설명 문장이 보인다', (kind) => {
    const { container } = render(
      <HypotheticalDiagram
        {...base}
        kind={kind}
        series={[
          { name: '가', values: [1, -2, 3] },
          { name: '나', values: [1, 1, 3] },
        ]}
      />,
    )

    const svg = container.querySelector('svg[viewBox]')
    expect(svg?.getAttribute('aria-hidden')).toBe('true')
    expect(screen.getByText('가상의 예')).not.toBeNull()
    expect(screen.getByText('둘 다 같은 값에서 끝난다.').tagName).toBe('FIGCAPTION')
    expect(container.querySelector(kind === 'line' ? 'polyline' : 'rect')).not.toBeNull()
  })

  it('수치가 그릴 수 없는 모양이면 그림 없이 설명 문장만 보인다', () => {
    const { container } = render(
      <HypotheticalDiagram {...base} kind="line" series={[{ name: '가', values: [1, 2] }]} />,
    )

    expect(container.querySelector('svg')).toBeNull()
    expect(screen.getByText('둘 다 같은 값에서 끝난다.')).not.toBeNull()
    expect(screen.getByText('가상의 예')).not.toBeNull()
  })

  it.each([
    ['점이 하나', ['1일'], [{ name: '가', values: [1] }]],
    ['계열이 없음', ['1일', '2일'], []],
    ['계열이 넷', ['1일', '2일'], Array.from({ length: 4 }, () => ({ name: '가', values: [1, 2] }))],
    ['길이가 다름', ['1일', '2일'], [{ name: '가', values: [1, 2, 3] }]],
    ['무한대', ['1일', '2일'], [{ name: '가', values: [1, Infinity] }]],
    ['NaN', ['1일', '2일'], [{ name: '가', values: [Number.NaN, 2] }]],
  ])('%s이면 그릴 수 없다', (_, categories, series) => {
    expect(isDrawable('line', categories, series)).toBe(false)
  })

  it('이 화면이 모르는 종류면 그림 없이 설명 문장만 보인다', () => {
    const { container } = render(
      <HypotheticalDiagram {...base} kind={null} series={[{ name: '가', values: [1, 2, 3] }]} />,
    )

    expect(isDrawable(null, base.categories, [{ name: '가', values: [1, 2, 3] }])).toBe(false)
    expect(container.querySelector('svg')).toBeNull()
    expect(screen.getByText('둘 다 같은 값에서 끝난다.')).not.toBeNull()
  })

  it('막대 도식의 기준선은 0이라 음수 막대는 기준선 아래로, 양수 막대는 위로 자란다', () => {
    const { container } = render(
      <HypotheticalDiagram {...base} kind="bar" series={[{ name: '가', values: [-20, 10, 15] }]} />,
    )

    const lines = container.querySelectorAll('svg line')
    expect(lines).toHaveLength(1)
    const zero = Number(lines[0].getAttribute('y1'))
    const [negative, positive] = [...container.querySelectorAll('rect')]
    // 음수 막대는 기준선에서 시작해 아래로, 양수 막대는 기준선에서 끝난다.
    expect(Number(negative.getAttribute('y'))).toBeCloseTo(zero)
    expect(
      Number(positive.getAttribute('y')) + Number(positive.getAttribute('height')),
    ).toBeCloseTo(zero)
  })

  it('선 도식은 그림 바닥에 기준선을 두고, 값이 0을 가로지르면 0에도 선을 하나 더 긋는다', () => {
    const { container, unmount } = render(
      <HypotheticalDiagram {...base} kind="line" series={[{ name: '가', values: [1, 2, 3] }]} />,
    )
    expect(container.querySelectorAll('svg line')).toHaveLength(1)
    unmount()

    const crossed = render(
      <HypotheticalDiagram {...base} kind="line" series={[{ name: '가', values: [-1, 2, 3] }]} />,
    )
    expect(crossed.container.querySelectorAll('svg line')).toHaveLength(2)
  })

  it('항목 이름이 칸보다 길면 눕히고, 그만큼 그림 높이를 늘린다', () => {
    const long = Array.from({ length: 12 }, (_, index) => `가나다라마바사아자차카${index}`)
    const { container, unmount } = render(
      <HypotheticalDiagram
        {...base}
        categories={long}
        kind="bar"
        series={[{ name: '가', values: long.map((_, index) => index + 1) }]}
      />,
    )
    const labels = [...container.querySelectorAll('svg text')]
    expect(labels).toHaveLength(12)
    expect(labels.every((label) => label.getAttribute('transform')?.startsWith('rotate(-'))).toBe(
      true,
    )
    const [, , , tallHeight] = container.querySelector('svg')!.getAttribute('viewBox')!.split(' ')
    unmount()

    const short = render(
      <HypotheticalDiagram {...base} kind="bar" series={[{ name: '가', values: [1, 2, 3] }]} />,
    )
    const shortLabels = [...short.container.querySelectorAll('svg text')]
    expect(shortLabels.every((label) => label.getAttribute('transform') === null)).toBe(true)
    const [, , , shortHeight] = short.container.querySelector('svg')!.getAttribute('viewBox')!.split(' ')
    expect(Number(tallHeight)).toBeGreaterThan(Number(shortHeight))
  })
})
