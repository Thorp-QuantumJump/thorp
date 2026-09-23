import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import {
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  syntheticStrategyName,
} from '@/test/resultFixtures'
import type { PublicResultDetail, ResultSummary } from '@/types/result'
import { COMPUTED_WEIGHTS_NOTE } from './conditionLine'
import { ConditionLine, METRIC_DEFINITIONS, METRIC_LABELS, ResultSummaryCard } from './ResultSummaryCard'

/** 005 T036·T098. FR-001~FR-003, FR-013~FR-015, quickstart 1·2. */

function withSummary(overrides: Partial<ResultSummary>): PublicResultDetail {
  return {
    ...resultDetailCompleted,
    summary: { ...resultDetailCompleted.summary!, ...overrides },
  }
}

/** 물음표에 마우스를 얹으면 열린다(초점으로도 열린다 — `InlineHelp.test.tsx`). */
async function openHelp(fullLabel: string) {
  await userEvent.setup().hover(screen.getByRole('button', { name: `${fullLabel} 도움말` }))
}

describe('ResultSummaryCard', () => {
  it('핵심 숫자 3개를 짧은 이름과 함께 세로로 보이고 정의는 물음표 도움말에 있다', async () => {
    render(<ResultSummaryCard detail={resultDetailCompleted} />)

    const rows = screen.getAllByRole('term')
    expect(rows.map((row) => row.textContent?.replace('?', ''))).toEqual([
      METRIC_LABELS.netTotalReturn.short,
      METRIC_LABELS.maxDrawdown.short,
      METRIC_LABELS.totalTradingCost.short,
    ])
    expect(screen.getByText('16.67%')).not.toBeNull()
    expect(screen.getByText('-4.18%')).not.toBeNull()
    expect(screen.getByText('17,078원')).not.toBeNull()
    // 정의는 본문에 펼쳐 두지 않는다.
    for (const definition of Object.values(METRIC_DEFINITIONS)) {
      expect(screen.queryByText(definition)).toBeNull()
    }
    expect(METRIC_DEFINITIONS.maxDrawdown).toContain('시작 금액 대비 손실과 다릅니다')

    await openHelp(METRIC_LABELS.maxDrawdown.full)
    expect(await screen.findByText(METRIC_DEFINITIONS.maxDrawdown)).not.toBeNull()
  })

  it('수익률 도움말에 정의와 종료 시 청산 구분이 함께 있다 (FR-015)', async () => {
    render(<ResultSummaryCard detail={resultDetailCompleted} />)

    await openHelp(METRIC_LABELS.netTotalReturn.full)
    expect(await screen.findByText(METRIC_DEFINITIONS.netTotalReturn)).not.toBeNull()
    expect(screen.getByText(/종료일 시가평가 기준 수익률은 16\.67%/).textContent).toContain(
      '청산 비용 3,996원을 포함해 16.63%',
    )
  })

  it('청산을 가정하지 않은 결과는 도움말에서 시가평가 기준이라고 밝힌다', async () => {
    render(<ResultSummaryCard detail={withSummary({ liquidation: null })} />)

    await openHelp(METRIC_LABELS.netTotalReturn.full)
    expect(
      await screen.findByText(/종료일 시가평가 기준이며 종료일에 모두 파는 경우를 가정하지 않았습니다/),
    ).not.toBeNull()
  })

  it('요약이 없으면 숫자 자리를 그리지 않고 상태 문장이 쓸 자리를 비워 둔다', () => {
    const { container } = render(<ResultSummaryCard detail={resultDetailCancelledNoSummary} />)

    expect(screen.queryByRole('definition')).toBeNull()
    // `\b`는 한글 경계를 모르므로 금액은 숫자 바로 뒤의 '원'으로 찾는다.
    expect(container.textContent).not.toMatch(/\d%|\d원/)
    expect(container.textContent).not.toContain('계산되지 않았습니다')
  })

  it('수익률과 최대 하락 폭이 모두 0이면 좋은 결과로 읽히지 않게 본문에 설명한다', () => {
    const { container } = render(
      <ResultSummaryCard
        detail={withSummary({ netTotalReturn: '0.000000', maxDrawdown: '0.000000', tradeCount: 0 })}
      />,
    )

    expect(container.textContent).toContain('손실이 없었다는 평가가 아닙니다')
    expect(container.textContent).toContain('체결된 거래가 없습니다')
  })
})

describe('ConditionLine', () => {
  it('분석 기간·비교 대상·비용 적용을 한 묶음으로 보인다 (FR-003)', () => {
    render(<ConditionLine detail={resultDetailCompleted} />)

    const condition = screen.getByLabelText('분석 조건')
    expect(within(condition).getByText('분석 기간 2023-07-03 ~ 2024-12-31')).not.toBeNull()
    expect(
      within(condition).getByText(`기준 ${syntheticStrategyName} · 비교 대상 없음`),
    ).not.toBeNull()
    expect(
      within(condition).getByText('비용 반영 (PROVISIONAL-TEST-1, 임시 비용 기준)'),
    ).not.toBeNull()
  })

  it('규칙이 계산한 비중이면 출처 문장을 보이고 사용자 비중이면 보이지 않는다', () => {
    const { rerender } = render(<ConditionLine detail={resultDetailCompleted} />)
    expect(screen.getByText(COMPUTED_WEIGHTS_NOTE)).not.toBeNull()

    rerender(<ConditionLine detail={{ ...resultDetailCompleted, weightsSource: 'USER' }} />)
    expect(screen.queryByText(COMPUTED_WEIGHTS_NOTE)).toBeNull()
  })

  it('등록표에서 이름을 찾지 못하면 전략 식별자를 그대로 보인다', () => {
    render(<ConditionLine detail={{ ...resultDetailCompleted, strategyName: null }} />)

    expect(screen.getByText('기준 WAVE_LEVELING · 비교 대상 없음')).not.toBeNull()
  })
})
