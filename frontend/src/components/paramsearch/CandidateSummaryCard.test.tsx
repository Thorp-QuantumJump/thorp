import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  completedCandidate,
  configFixture,
  excludedCandidate,
} from '@/test/paramSearchFixtures'
import { CandidateSummaryCard } from './CandidateSummaryCard'

/**
 * 계산하지 않은 값을 0으로 채우지 않는다(T086, FR-011, 원칙 VI).
 *
 * <p><b>"0%"는 "수익이 없었다"는 주장이다.</b> 제외·실패·미실행 후보에 그것을 쓰면 화면이 하지 않은
 * 계산의 결과를 말하게 된다. 그래서 값이 없으면 자리를 비우고 <b>왜 없는지</b>를 적는다.
 */
describe('CandidateSummaryCard', () => {
  it('세 숫자를 응답에서 읽어 보인다', () => {
    const { container } = render(
      <CandidateSummaryCard candidate={completedCandidate} config={configFixture} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('비용 차감 후 수익')
    expect(text).toContain('+30.00%')
    expect(text).toContain('가장 크게 줄었던 폭')
    expect(text).toContain('-10.00%')
    expect(text).toContain('거래비용')
    expect(text).toContain('17,078원')
  })

  it('전략 이름과 축 라벨을 응답에서만 읽는다', () => {
    const { container } = render(
      <CandidateSummaryCard candidate={completedCandidate} config={configFixture} />,
    )

    expect(container.textContent).toContain('알파 기울기')
    expect(container.textContent).toContain('기울기 관측 창')
    expect(container.textContent).toContain('5거래일')
  })

  it('전략 이름을 끄면 설정값만 남는다 — 목록이 위에서 한 번 밝힌 경우다', () => {
    const { container } = render(
      <CandidateSummaryCard
        candidate={completedCandidate}
        config={configFixture}
        showStrategy={false}
      />,
    )

    expect(container.textContent).not.toContain('알파 기울기')
    expect(container.textContent).toContain('기울기 관측 창')
    expect(container.textContent).toContain('5거래일')
  })

  it('객체형 설정값은 등록된 표시 문구로 보인다', () => {
    const candidate = {
      ...completedCandidate,
      parameters: { rebalancePeriod: { mode: 'CALENDAR', unit: 'MONTH_END' } },
    }
    const config = {
      ...configFixture,
      space: {
        ALPHA_TILT: {
          ...configFixture.space.ALPHA_TILT,
          axes: [
            {
              name: 'rebalancePeriod',
              label: '리밸런싱 주기',
              unit: null,
              values: [{ mode: 'CALENDAR', unit: 'MONTH_END' }],
              valueLabels: ['월말'],
            },
          ],
        },
      },
    }

    const { container } = render(<CandidateSummaryCard candidate={candidate} config={config} />)

    expect(container.textContent).toContain('리밸런싱 주기')
    expect(container.textContent).toContain('월말')
    expect(container.textContent).not.toContain('[object Object]')
  })

  it('config 가 없으면 축 이름을 그대로 쓰고 지어내지 않는다', () => {
    const { container } = render(
      <CandidateSummaryCard candidate={completedCandidate} config={null} />,
    )

    expect(container.textContent).toContain('ALPHA_TILT')
    expect(container.textContent).toContain('tiltWindow')
    expect(container.textContent).toContain('5')
  })

  it('계산하지 않은 후보를 0%로 그리지 않고 사유를 적는다', () => {
    const { container } = render(
      <CandidateSummaryCard candidate={excludedCandidate} config={configFixture} />,
    )
    const text = container.textContent ?? ''

    expect(text).not.toContain('0.00%')
    expect(text).not.toContain('0원')
    expect(text).toContain('계산 전 제외')
    expect(text).toContain('관측 창이 자료 구간보다 깁니다.')
  })

  it('결과가 없는 후보는 비교에 담을 수 없다', () => {
    render(
      <CandidateSummaryCard
        candidate={excludedCandidate}
        config={configFixture}
        compareChecked={false}
        onCompareToggle={vi.fn()}
      />,
    )

    const checkbox = screen.getByRole('checkbox') as HTMLInputElement
    expect(checkbox.disabled).toBe(true)
    expect(screen.getByText('결과가 없어 담을 수 없습니다')).toBeTruthy()
  })

  it('결과가 있는 후보는 비교에 담을 수 있다', () => {
    render(
      <CandidateSummaryCard
        candidate={completedCandidate}
        config={configFixture}
        compareChecked={false}
        onCompareToggle={vi.fn()}
      />,
    )

    expect((screen.getByRole('checkbox') as HTMLInputElement).disabled).toBe(false)
  })
})
