import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SelectionMeaningNotice } from './SelectionMeaningNotice'

function renderNotice(selectedCount: number, hasZeroWeight: boolean) {
  return render(
    <SelectionMeaningNotice selectedCount={selectedCount} hasZeroWeight={hasZeroWeight} />,
  )
}

function candidateText(container: HTMLElement): string {
  return container.querySelector('[data-slot="candidate-meaning"]')?.textContent ?? ''
}

function zeroWeightNotice(container: HTMLElement): Element | null {
  return container.querySelector('[data-slot="zero-weight-meaning"]')
}

describe('후보 집합과 0%의 뜻 안내', () => {
  it('아무것도 고르지 않았으면 안내 자체가 없다', () => {
    const { container } = renderNotice(0, false)
    expect(container.querySelector('[data-slot="selection-meaning"]')).toBeNull()
  })

  it('섹터를 하나 이상 고르면 후보 집합 안내가 항상 보인다 (FR-009)', () => {
    const { container } = renderNotice(1, false)

    const text = candidateText(container)
    expect(text).toContain('후보 집합')
    // 고른 섹터를 매번 모두 보유한다는 뜻이 아니라는 사실이 문장에 있어야 한다.
    expect(text).toMatch(/모두 보유한다는 뜻이\s*아니며/)
    // 사용자가 넣은 비중과 자동 전략의 출력 비중을 구분한다.
    expect(text).toContain('내가 정한 비중')
    expect(text).toContain('구분')
  })

  it('0인 섹터가 없으면 0% 안내가 보이지 않는다', () => {
    const { container } = renderNotice(3, false)
    expect(zeroWeightNotice(container)).toBeNull()
  })

  it('0인 섹터가 있을 때만 0% 안내가 함께 보인다 (FR-016)', () => {
    const { container } = renderNotice(3, true)

    const text = zeroWeightNotice(container)?.textContent ?? ''
    expect(text).toContain('0%')
    // 후보 집합에는 남는다는 사실이 핵심이다. 빠졌다고 읽히면 SC-010과 어긋난다.
    expect(text).toContain('후보 집합에는')
    expect(text).toMatch(/그대로 남습니다/)
  })

  it('두 문장은 같은 자리에 나란히 놓인다', () => {
    const { container } = renderNotice(2, true)

    const group = container.querySelector('[data-slot="selection-meaning"]')
    expect(group?.querySelector('[data-slot="candidate-meaning"]')).not.toBeNull()
    expect(group?.querySelector('[data-slot="zero-weight-meaning"]')).not.toBeNull()
  })

  it('안내는 읽는 문장뿐이고 입력 요소를 두지 않는다 (SC-005)', () => {
    const { container } = renderNotice(2, true)
    expect(container.querySelectorAll('input, select, textarea, button')).toHaveLength(0)
  })
})
