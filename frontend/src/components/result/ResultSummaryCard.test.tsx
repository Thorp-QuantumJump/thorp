import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  syntheticStrategyName,
} from '@/test/resultFixtures'
import type { PublicResultDetail, ResultSummary } from '@/types/result'
import { COMPUTED_WEIGHTS_NOTE } from './conditionLine'
import { METRIC_DEFINITIONS, ResultConditionLine, ResultSummaryCard } from './ResultSummaryCard'

/** 005 T036. FR-001~FR-003, FR-013~FR-015, quickstart 1·2. */

function withSummary(overrides: Partial<ResultSummary>): PublicResultDetail {
  return {
    ...resultDetailCompleted,
    summary: { ...resultDetailCompleted.summary!, ...overrides },
  }
}

describe('ResultSummaryCard', () => {
  it('핵심 숫자 3개와 요청 시 각 정의를 보인다', async () => {
    render(<ResultSummaryCard detail={resultDetailCompleted} />)

    expect(screen.getByText('16.67%')).not.toBeNull()
    expect(screen.getByText('-4.18%')).not.toBeNull()
    expect(screen.getByText('17,078원')).not.toBeNull()
    for (const [label, definition] of [
      ['수익률', METRIC_DEFINITIONS.netTotalReturn],
      ['MDD', METRIC_DEFINITIONS.maxDrawdown],
      ['거래 수수료 등', METRIC_DEFINITIONS.totalTradingCost],
    ] as const) {
      const help = screen.getByRole('button', { name: `${label} 도움말` })
      fireEvent.focus(help)
      const bubble = await screen.findByText(definition)
      expect(bubble).not.toBeNull()
      if (label === '수익률') {
        expect(bubble.closest('[data-side]')?.getAttribute('data-side')).toBe('bottom')
        expect(bubble.closest('[data-align]')?.getAttribute('data-align')).toBe('start')
      }
      fireEvent.blur(help)
    }
    expect(METRIC_DEFINITIONS.maxDrawdown).toContain('시작 금액 대비 손실과 다릅니다')

    expect(screen.queryByLabelText('분석 조건')).toBeNull()
    expect(screen.getByText('이전 고점 회복까지 9거래일')).not.toBeNull()
    expect(screen.queryByText(/종료일 시가평가 기준 수익률은 16\.67%/)).toBeNull()
  })

  it('분석 조건을 요약 탭에서 쓸 수 있도록 별도로 렌더링한다', () => {
    render(<ResultConditionLine detail={resultDetailCompleted} />)

    const condition = screen.getByLabelText('분석 조건')
    expect(within(condition).getByText('분석 기간 2023-07-03 ~ 2024-12-31')).not.toBeNull()
    expect(
      within(condition).getByText(`기준 ${syntheticStrategyName} · 비교 대상 없음`),
    ).not.toBeNull()
    expect(
      within(condition).getByText('비용 반영 · 임시 비용 기준'),
    ).not.toBeNull()
    expect(condition.textContent).not.toContain('PROVISIONAL-TEST-1')
  })

  it('요약이 없으면 숫자 자리를 그리지 않고 상태 문장이 쓸 자리를 비워 둔다', () => {
    const { container } = render(<ResultSummaryCard detail={resultDetailCancelledNoSummary} />)

    expect(screen.queryByRole('definition')).toBeNull()
    expect(container.querySelector('section')).toBeNull()
    // `\b`는 한글 경계를 모르므로 금액은 숫자 바로 뒤의 '원'으로 찾는다.
    expect(container.textContent).not.toMatch(/\d%|\d원/)
    expect(container.textContent).not.toContain('계산되지 않았습니다')
  })

  it('수익률과 가장 크게 줄었던 폭이 모두 0이면 좋은 결과로 읽히지 않게 설명한다', () => {
    const { container } = render(
      <ResultSummaryCard
        detail={withSummary({ netTotalReturn: '0.000000', maxDrawdown: '0.000000', tradeCount: 0 })}
      />,
    )

    expect(container.textContent).toContain('손실이 없었다는 평가가 아닙니다')
    expect(container.textContent).toContain('체결된 거래가 없습니다')
  })

  it('규칙이 계산한 비중이면 출처 문장을 보이고 사용자 비중이면 보이지 않는다', () => {
    const { rerender } = render(<ResultConditionLine detail={resultDetailCompleted} />)
    expect(screen.getByText(COMPUTED_WEIGHTS_NOTE)).not.toBeNull()

    rerender(
      <ResultConditionLine detail={{ ...resultDetailCompleted, weightsSource: 'USER' }} />,
    )
    expect(screen.queryByText(COMPUTED_WEIGHTS_NOTE)).toBeNull()
  })

  it('등록표에서 이름을 찾지 못하면 전략 식별자를 그대로 보인다', () => {
    render(
      <ResultConditionLine detail={{ ...resultDetailCompleted, strategyName: null }} />,
    )

    expect(screen.getByText('기준 WAVE_LEVELING · 비교 대상 없음')).not.toBeNull()
  })

  it('요약 카드는 종료일 평가 안내를 차트 위에 보이지 않는다', () => {
    render(<ResultSummaryCard detail={withSummary({ liquidation: null })} />)

    expect(screen.queryByText(/종료일 시가평가 기준이며 종료일에 모두 파는 경우를 가정하지 않았습니다/)).toBeNull()
  })
})
