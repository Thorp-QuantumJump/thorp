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
    expect(isDrawable(categories, series)).toBe(false)
  })
})
