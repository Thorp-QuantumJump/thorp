import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SelectedInstrumentsRail } from './SelectedInstrumentsRail'

function renderRail() {
  return render(
    <SelectedInstrumentsRail
      selected={[]}
      minimumCount={2}
      onRemove={vi.fn()}
      onProceed={vi.fn()}
      proceedDisabled
    />,
  )
}

describe('선택 요약의 후보 집합 도움말 (002 FR-009)', () => {
  it('제목 옆 물음표를 열면 고른 대상이 후보 집합이며 모두 보유한다는 뜻이 아님을 알린다', async () => {
    renderRail()

    const trigger = screen.getByRole('button', { name: '선택한 투자 대상 도움말' })
    fireEvent.focus(trigger)
    const tooltip = await screen.findByRole('tooltip')

    await waitFor(() => expect(tooltip.textContent).toContain('후보 집합'))
    // 고른 대상을 매번 모두 보유한다는 뜻이 아니라는 사실과, 비중을 어디서 정하는지를 함께 알린다.
    expect(tooltip.textContent).toMatch(/모두 보유한다는 뜻이\s*아니며/)
    expect(tooltip.textContent).toContain('고정 비중')
    expect(tooltip.textContent).toContain('다음 단계')
  })
})
