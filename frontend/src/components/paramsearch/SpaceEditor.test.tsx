import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { previewFixture } from '@/test/paramSearchFixtures'
import type { SearchSpaceSelection, StrategySpaceSummary } from '@/types/paramSearch'
import { SpaceEditor } from './SpaceEditor'

/** 등록표에 없는, 이 테스트에서 지어낸 전략과 축. 화면이 이것을 알 방법은 응답뿐이다. */
const inventedSpace: StrategySpaceSummary[] = [
  {
    strategyId: 'MOON_PHASE_BLEND',
    strategyName: '달 위상 혼합',
    axes: [
      {
        axis: 'phaseDepth',
        label: '위상 깊이',
        unit: '단계',
        values: [1, 2, 3, 4],
        valueLabels: ['1', '2', '3', '4'],
        defaultValue: 2,
        allowedRange: { min: 1, max: 9, step: 1, values: null },
      },
    ],
    combinations: 4,
  },
]

/** 제어 컴포넌트를 여러 번 눌러 보기 위한 최소 껍데기. */
function Harness({ spaceSummary }: { spaceSummary: StrategySpaceSummary[] }) {
  const [value, setValue] = useState<SearchSpaceSelection>({})
  return <SpaceEditor spaceSummary={spaceSummary} value={value} onChange={setValue} />
}

/**
 * 전략이 늘어도 화면 코드를 고치지 않는다(T136, SC-010, FR-025).
 *
 * <p>동작만 보는 테스트로는 이것을 잡을 수 없다. 전략 ID 분기가 하나 생겨도 그 전략을 쓰는
 * 테스트는 여전히 통과한다. 그래서 <b>지어낸 전략으로 렌더되는지</b>와 <b>소스에 전략 지식이
 * 있는지</b>를 함께 본다.
 */
describe('SpaceEditor', () => {
  it('응답에만 있는 전략과 축도 코드 변경 없이 렌더된다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('달 위상 혼합')
    expect(text).toContain('위상 깊이')
    expect(text).toContain('단계')
    expect(text).toContain('등록 허용 범위 1~9 · 간격 1')
    for (const value of ['1', '2', '3', '4']) {
      expect(screen.getByRole('button', { name: new RegExp(`^${value}`) })).toBeTruthy()
    }
  })

  it('축이 없는 전략은 조합이 1개라고 알린다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={previewFixture.spaceSummary} value={{}} onChange={vi.fn()} />,
    )

    expect(container.textContent).toContain('탐색 축이 없는 전략입니다. 조합은 1개입니다.')
  })

  it('처음에는 등록된 후보값이 모두 켜져 있다', () => {
    render(<SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />)

    for (const value of ['1', '2', '3', '4']) {
      const button = screen.getByRole('button', { name: new RegExp(`^${value}`) })
      expect(button.getAttribute('aria-pressed')).toBe('true')
    }
  })

  it('끈 값만 빠지고 남는 값은 모두 등록된 후보값이다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: /^3/ }))

    expect(onChange).toHaveBeenCalledTimes(1)
    const next = onChange.mock.calls[0][0] as SearchSpaceSelection
    expect(next.MOON_PHASE_BLEND.phaseDepth).toEqual([1, 2, 4])
    // 등록되지 않은 값이 끼어들 자리가 없다.
    for (const picked of next.MOON_PHASE_BLEND.phaseDepth) {
      expect(inventedSpace[0].axes[0].values).toContain(picked)
    }
  })

  it('마지막 하나는 끌 수 없다 — 축이 비면 돌릴 조합이 없다', async () => {
    const user = userEvent.setup()
    render(<Harness spaceSummary={inventedSpace} />)

    for (const value of ['1', '2', '3']) {
      await user.click(screen.getByRole('button', { name: new RegExp(`^${value}`) }))
    }
    const last = screen.getByRole('button', { name: /^4/ })
    expect(last.getAttribute('aria-pressed')).toBe('true')

    await user.click(last)

    expect(screen.getByRole('button', { name: /^4/ }).getAttribute('aria-pressed')).toBe('true')
  })

  it('전부 다시 켜면 좁히지 않은 것과 같아진다', async () => {
    const user = userEvent.setup()
    render(<Harness spaceSummary={inventedSpace} />)

    const target = () => screen.getByRole('button', { name: /^2/ })
    await user.click(target())
    expect(target().getAttribute('aria-pressed')).toBe('false')

    await user.click(target())
    expect(target().getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText('4/4개 선택 · 등록 허용 범위 1~9 · 간격 1')).toBeTruthy()
  })

  it('기본 시작값을 표시하되 추천·최적이라고 쓰지 않는다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('기본 시작값')
    for (const banned of ['추천', '최적', '1위', '우승']) {
      expect(text).not.toContain(banned)
    }
  })
})

/**
 * 후보값이 객체인 축(등록표의 리밸런싱 주기가 그렇다).
 *
 * <p>이 축이 화면에서 두 가지로 깨졌다. 그리는 쪽은 값을 `String()`으로 찍어 `[object Object]`를
 * 보였고, 견주는 쪽은 `===`로 봐서 <b>응답을 다시 받아 새 객체가 오면 켜 둔 값이 꺼진 것처럼</b>
 * 보였다. 둘 다 후보값이 숫자·문자열이면 드러나지 않으므로 객체 축으로만 잡을 수 있다.
 */
describe('SpaceEditor — 후보값이 객체인 축', () => {
  const objectSpace: StrategySpaceSummary[] = [
    {
      strategyId: 'TIDE_ROTATION',
      strategyName: '조수 순환',
      axes: [
        {
          axis: 'rebalancePeriod',
          label: '재조정 주기',
          unit: null,
          values: [
            { mode: 'CALENDAR', unit: 'MONTH_END' },
            { mode: 'TRADING_DAYS', days: 21 },
          ],
          valueLabels: ['월말', '21거래일'],
          defaultValue: { mode: 'CALENDAR', unit: 'MONTH_END' },
          allowedRange: null,
        },
      ],
      combinations: 2,
    },
  ]

  /** 서버가 같은 내용을 다시 준 것. 값은 같은 뜻이지만 객체는 전부 새것이다. */
  function refetched(): StrategySpaceSummary[] {
    return JSON.parse(JSON.stringify(objectSpace)) as StrategySpaceSummary[]
  }

  it('값이 아니라 등록표가 적어 둔 문구를 그린다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={objectSpace} value={{}} onChange={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('월말')
    expect(text).toContain('21거래일')
    expect(text).not.toContain('[object Object]')
    // 값의 속살(전송 형식)이 사용자에게 새어 나오지 않는다.
    expect(text).not.toContain('MONTH_END')
    expect(text).not.toContain('TRADING_DAYS')
  })

  it('객체 후보에도 기본 시작값 표시가 붙는다', () => {
    render(<SpaceEditor spaceSummary={objectSpace} value={{}} onChange={vi.fn()} />)

    expect(screen.getByRole('button', { name: /^월말/ }).textContent).toContain('기본 시작값')
  })

  it('끈 값은 원래 객체 그대로 나간다 — 문구를 보내지 않는다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<SpaceEditor spaceSummary={objectSpace} value={{}} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: /^21거래일/ }))

    const next = onChange.mock.calls[0][0] as SearchSpaceSelection
    expect(next.TIDE_ROTATION.rebalancePeriod).toEqual([{ mode: 'CALENDAR', unit: 'MONTH_END' }])
  })

  it('응답을 다시 받아 새 객체가 와도 켜 둔 값이 켜진 채로 남는다', () => {
    // 21거래일만 끈 상태. 이 값은 앞선 응답의 객체이고, 아래 목록은 새 응답의 객체다.
    const narrowed: SearchSpaceSelection = {
      TIDE_ROTATION: { rebalancePeriod: [{ mode: 'CALENDAR', unit: 'MONTH_END' }] },
    }

    render(<SpaceEditor spaceSummary={refetched()} value={narrowed} onChange={vi.fn()} />)

    expect(screen.getByRole('button', { name: /^월말/ }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: /^21거래일/ }).getAttribute('aria-pressed')).toBe(
      'false',
    )
    expect(screen.getByText('1/2개 선택')).toBeTruthy()
  })
})
