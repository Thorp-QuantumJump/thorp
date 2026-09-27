import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SelectionMeaningNotice } from './SelectionMeaningNotice'

function renderNotice(selectedCount: number) {
  return render(<SelectionMeaningNotice selectedCount={selectedCount} />)
}

function candidateText(container: HTMLElement): string {
  return container.querySelector('[data-slot="candidate-meaning"]')?.textContent ?? ''
}

describe('후보 집합의 뜻 안내', () => {
  it('아무것도 고르지 않았으면 안내 자체가 없다', () => {
    const { container } = renderNotice(0)
    expect(container.querySelector('[data-slot="selection-meaning"]')).toBeNull()
  })

  it('하나 이상 고르면 후보 집합 안내가 항상 보인다 (FR-009)', () => {
    const { container } = renderNotice(1)

    const text = candidateText(container)
    expect(text).toContain('후보 집합')
    // 고른 대상을 매번 모두 보유한다는 뜻이 아니라는 사실이 문장에 있어야 한다.
    expect(text).toMatch(/모두 보유한다는 뜻이\s*아니며/)
    // 비중을 어디서 정하는지 알린다. 이 화면에는 그 입력이 없다.
    expect(text).toContain('내가 정한 비율대로')
    expect(text).toContain('다음 단계')
  })

  it('비중을 정하는 자리가 이 화면에 있다고 말하지 않는다', () => {
    const { container } = renderNotice(3)

    // 0%인 대상의 뜻(FR-016)은 비중을 실제로 넣는 다음 화면이 알린다.
    expect(container.querySelector('[data-slot="zero-weight-meaning"]')).toBeNull()
    expect(candidateText(container)).not.toMatch(/여기에 입력한/)
  })

  it('안내는 읽는 문장뿐이고 입력 요소를 두지 않는다 (SC-005)', () => {
    const { container } = renderNotice(2)
    expect(container.querySelectorAll('input, select, textarea, button')).toHaveLength(0)
  })
})
