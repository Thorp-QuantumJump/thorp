import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  syntheticStrategyName,
} from '@/test/resultFixtures'
import type { PublicResultDetail, ResultSummary } from '@/types/result'
import { COMPUTED_WEIGHTS_NOTE } from './conditionLine'
import { METRIC_DEFINITIONS, ResultSummaryCard, SUMMARY_NOT_CALCULATED } from './ResultSummaryCard'

/** 005 T036. FR-001~FR-003, FR-013~FR-015, quickstart 1·2. */

function withSummary(overrides: Partial<ResultSummary>): PublicResultDetail {
  return {
    ...resultDetailCompleted,
    summary: { ...resultDetailCompleted.summary!, ...overrides },
  }
}

describe('ResultSummaryCard', () => {
  it('핵심 숫자 3개와 각 정의, 조건 한 줄, 청산 구분을 텍스트로 보인다', () => {
    render(<ResultSummaryCard detail={resultDetailCompleted} completedThrough="2024-12-31" />)

    expect(screen.getByText('16.67%')).not.toBeNull()
    expect(screen.getByText('-4.18%')).not.toBeNull()
    expect(screen.getByText('17,078원')).not.toBeNull()
    for (const definition of Object.values(METRIC_DEFINITIONS)) {
      expect(screen.getByText(definition)).not.toBeNull()
    }
    expect(METRIC_DEFINITIONS.maxDrawdown).toContain('시작 금액 대비 손실과 다릅니다')

    const condition = screen.getByLabelText('분석 조건')
    expect(within(condition).getByText('분석 기간 2023-07-03 ~ 2024-12-31')).not.toBeNull()
    expect(
      within(condition).getByText(`기준 ${syntheticStrategyName} · 비교 대상 없음`),
    ).not.toBeNull()
    expect(
      within(condition).getByText('비용 반영 (PROVISIONAL-TEST-1, 임시 비용 기준)'),
    ).not.toBeNull()

    expect(screen.getByText(/종료일 시가평가 기준 수익률은 16\.67%/).textContent).toContain(
      '청산 비용 3,996원을 포함해 16.63%',
    )
  })

  it('요약이 없으면 숫자 자리를 그리지 않고 계산되지 않았다고 쓴다', () => {
    const { container } = render(
      <ResultSummaryCard detail={resultDetailCancelledNoSummary} completedThrough={null} />,
    )

    expect(screen.queryByRole('definition')).toBeNull()
    // `\b`는 한글 경계를 모르므로 금액은 숫자 바로 뒤의 '원'으로 찾는다.
    expect(container.textContent).not.toMatch(/\d%|\d원/)
    expect(container.textContent).toContain(SUMMARY_NOT_CALCULATED)
    expect(container.textContent).toContain('완료된 거래일이 없습니다')
  })

  it('실행이 계속 돈 취소 결과는 완료 범위를 날짜로 밝힌다', () => {
    const detail: PublicResultDetail = {
      ...resultDetailCancelledNoSummary,
      summaryUnavailable: 'CANCELLED_WHILE_RUN_CONTINUED',
    }

    const { container, rerender } = render(
      <ResultSummaryCard detail={detail} completedThrough="2024-03-29" />,
    )
    expect(container.textContent).toContain(SUMMARY_NOT_CALCULATED)
    expect(container.textContent).toContain('2024-03-29까지 계산이 진행되었습니다.')

    rerender(<ResultSummaryCard detail={detail} completedThrough={null} />)
    expect(container.textContent).toContain('완료 범위는 확인할 수 없습니다.')
  })

  it('수익률과 가장 크게 줄었던 폭이 모두 0이면 좋은 결과로 읽히지 않게 설명한다', () => {
    const { container } = render(
      <ResultSummaryCard
        detail={withSummary({ netTotalReturn: '0.000000', maxDrawdown: '0.000000', tradeCount: 0 })}
        completedThrough={null}
      />,
    )

    expect(container.textContent).toContain('손실이 없었다는 평가가 아닙니다')
    expect(container.textContent).toContain('체결된 거래가 없습니다')
  })

  it('규칙이 계산한 비중이면 출처 문장을 보이고 사용자 비중이면 보이지 않는다', () => {
    const { rerender } = render(
      <ResultSummaryCard detail={resultDetailCompleted} completedThrough={null} />,
    )
    expect(screen.getByText(COMPUTED_WEIGHTS_NOTE)).not.toBeNull()

    rerender(
      <ResultSummaryCard
        detail={{ ...resultDetailCompleted, weightsSource: 'USER' }}
        completedThrough={null}
      />,
    )
    expect(screen.queryByText(COMPUTED_WEIGHTS_NOTE)).toBeNull()
  })

  it('등록표에서 이름을 찾지 못하면 전략 식별자를 그대로 보인다', () => {
    render(
      <ResultSummaryCard
        detail={{ ...resultDetailCompleted, strategyName: null }}
        completedThrough={null}
      />,
    )

    expect(screen.getByText('기준 WAVE_LEVELING · 비교 대상 없음')).not.toBeNull()
  })

  it('청산을 가정하지 않은 결과는 시가평가 기준이라고 밝힌다', () => {
    render(
      <ResultSummaryCard detail={withSummary({ liquidation: null })} completedThrough={null} />,
    )

    expect(screen.getByText(/종료일 시가평가 기준이며 종료일에 모두 파는 경우를 가정하지 않았습니다/)).not.toBeNull()
  })
})
